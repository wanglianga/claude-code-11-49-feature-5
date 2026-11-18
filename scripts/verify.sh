#!/usr/bin/env bash
# 验收回放脚本：多院重复挂号合并、误并拆分、并发决议、来源证件更正、关账后调整、
# 渐进式来源接入（批次覆盖/稳定性/暂存隔离）
# 前置条件：服务已启动且为全新播种数据（图谱版本 = 0）。重置方式见 README。
set -euo pipefail

BASE=${BASE_URL:-http://127.0.0.1:8080}
OP1=${OP1:-op_zhang}
OP2=${OP2:-op_li}

pass=0; fail=0
ok()   { pass=$((pass+1)); echo "  ✔ $1"; }
bad()  { fail=$((fail+1)); echo "  ✘ $1"; }
check(){ # check <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望=$3 实际=$2）"; fi
}
check_ne(){ # check_ne <描述> <实际> <不应等于>
  if [ "$2" != "$3" ]; then ok "$1"; else bad "$1（不应等于 $3）"; fi
}

get(){ curl -s --retry 3 --retry-all-errors --retry-delay 1 "$BASE$1"; }
post(){ # post <path> <json> <operator>
  curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' \
       -H "X-Operator-Id: ${3:-system}" -d "${2:-{}}"
}
post_status(){ # post_status <path> <json> <operator> -> http code, body in /tmp/mpi-verify-body.json
  curl -s -o /tmp/mpi-verify-body.json -w "%{http_code}" -X POST "$BASE$1" \
       -H 'Content-Type: application/json' -H "X-Operator-Id: ${3:-system}" -d "${2:-{}}"
}
get_status(){ # get_status <path> -> http code, body in /tmp/mpi-verify-body.json
  curl -s -o /tmp/mpi-verify-body.json -w "%{http_code}" "$BASE$1"
}

version(){ get /api/graph/version | jq -r '.graphVersion'; }
identity_id(){ get /api/source-identities | jq -r --arg s "$1" --arg p "$2" \
  '.[] | select(.sourceSystem==$s and .sourcePatientId==$p) | .id'; }
find_candidate(){ get "/api/candidates?status=PENDING" | jq -r --arg a "$1" --arg b "$2" \
  '[.[] | select((.identityAId==$a and .identityBId==$b) or (.identityAId==$b and .identityBId==$a))][0].id // empty'; }
candidate_status(){ get "/api/candidates/$1" | jq -r '.status'; }
resolve_master(){ get "/api/resolve?sourceSystem=$1&sourcePatientId=$2" | jq -r '.masterId'; }
snap_resolve(){ get "/api/snapshots/$1/resolve?sourceSystem=$2&sourcePatientId=$3" | jq -r '.masterId'; }
latest_snapshot(){ get /api/snapshots | jq -r --arg t "$1" --arg s "$2" \
  '[.[] | select(.businessType==$t and .status==$s)][0].id // empty'; }
appt_field(){ get /api/biz/appointments | jq -r --arg p "$1" \
  '[.[] | select(.sourcePatientId==$p)][0].'"$2"; }
settle_field(){ get /api/biz/settlements | jq -r --arg p "$1" \
  '[.[] | select(.sourcePatientId==$p)][0].'"$2"; }
lab_field(){ get /api/biz/lab-reports | jq -r --arg p "$1" \
  '[.[] | select(.sourcePatientId==$p)][0].'"$2"; }
impact_count(){ get "/api/impacts?resolutionId=$1" | jq --arg t "$2" --arg a "$3" \
  '[.[] | select(.businessType==$t and .action==$a)] | length'; }

echo "=========================================================="
echo " MPI 验收回放  BASE=$BASE  操作员: $OP1 / $OP2"
echo "=========================================================="

V0=$(version)
if [ "$V0" != "0" ]; then
  echo "!! 当前图谱版本为 $V0，验收脚本要求全新播种数据（版本 0）。"
  echo "!! 请先重置数据库并重启服务（见 README「重置样例数据」）。"
  exit 1
fi

A1=$(identity_id REG_A P-A-001); B1=$(identity_id REG_B P-B-001)
A2=$(identity_id REG_A P-A-002); B2=$(identity_id REG_B P-B-002)
A3=$(identity_id REG_A P-A-003); B3=$(identity_id REG_B P-B-003)
A4=$(identity_id REG_A P-A-004); L1=$(identity_id LAB_A L-A-001)
S1=$(identity_id SETTLE_A S-A-001)
SN_APPT=$(latest_snapshot APPOINTMENT PUBLISHED)
SN_SETTLE=$(latest_snapshot SETTLEMENT PUBLISHED)
SN_LAB=$(latest_snapshot LAB PUBLISHED)
M_B2_ORIG=$(resolve_master REG_B P-B-002)
M_A2_ORIG=$(resolve_master REG_A P-A-002)
M_L1_ORIG=$(resolve_master LAB_A L-A-001)

echo
echo "【场景0】初始播种数据"
N_CAND=$(get "/api/candidates?status=PENDING" | jq 'length')
check "待处理候选对 5 个（含家庭共用号码 0.4 分对）" "$N_CAND" "5"
check "张三两院候选对评分 1.0" \
  "$(get "/api/candidates?status=PENDING" | jq -r --arg a "$A1" --arg b "$B1" '[.[] | select((.identityAId==$a and .identityBId==$b) or (.identityAId==$b and .identityBId==$a))][0].score')" "1"

echo
echo "【场景1】多院重复挂号：张三（REG_A/P-A-001 × REG_B/P-B-001）合并"
C_ZS=$(find_candidate "$A1" "$B1")
V=$(version)
R=$(post /api/resolutions/merge "{\"candidateId\":\"$C_ZS\",\"expectedGraphVersion\":$V,\"reason\":\"两院重复挂号，证件号一致\"}" "$OP1")
check "合并后图谱版本 +1" "$(echo "$R" | jq -r '.graphVersion')" "$((V+1))"
check "张三两来源解析到同一主体" "$(resolve_master REG_A P-A-001)" "$(resolve_master REG_B P-B-001)"
check "候选对状态变为 MERGED" "$(candidate_status "$C_ZS")" "MERGED"

echo
echo "【场景2】误并拆分 + 关账后调整（同名陈晨被人工误并）"
V=$(version)
R=$(post /api/resolutions/merge "{\"identityIds\":[\"$A2\",\"$B2\"],\"expectedGraphVersion\":$V,\"reason\":\"客服误并：同名同姓\"}" "$OP1")
M3=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
RID2=$(echo "$R" | jq -r '.id')
check "误并后两个陈晨同属新主体" "$(resolve_master REG_B P-B-002)" "$M3"
check "生成调整清单（已关账结算 ADJUSTMENT_LIST）" "$(impact_count "$RID2" SETTLEMENT ADJUSTMENT_LIST)" "1"
check "生成提醒切换影响（REMIND_SWITCH）" "$(impact_count "$RID2" APPOINTMENT REMIND_SWITCH)" "1"
check "生成检验归属影响（REATTRIBUTE）" "$(impact_count "$RID2" LAB REATTRIBUTE)" "1"
check "生成分析口径影响（INFO）" "$(impact_count "$RID2" ANALYTICS INFO)" "2"

CS_APPT=$(latest_snapshot APPOINTMENT CANDIDATE)
CODE=$(get_status "/api/snapshots/$CS_APPT/resolve?sourceSystem=REG_B&sourcePatientId=P-B-002")
check "候选快照未确认前下游不可读（409）" "$CODE" "409"

post "/api/snapshots/$CS_APPT/confirm" "{}" "$OP1" >/dev/null
check "确认预约候选快照后：预约归属切到合并主体" "$(appt_field P-B-002 masterId)" "$M3"
check "误并后果可见：提醒电话错用陈晨A号码" "$(appt_field P-B-002 contactPhone)" "13700000002"

CS_SETTLE=$(latest_snapshot SETTLEMENT CANDIDATE)
post "/api/snapshots/$CS_SETTLE/confirm" "{}" "$OP1" >/dev/null
check "已关账结算单不被改写（仍属原主体）" "$(settle_field P-A-002 masterId)" "$M_A2_ORIG"
check "已关账结算单状态仍 CLOSED" "$(settle_field P-A-002 status)" "CLOSED"
check "调整清单保持 PENDING 待财务人工处理" \
  "$(get "/api/impacts?resolutionId=$RID2" | jq -r --arg a "ADJUSTMENT_LIST" '[.[] | select(.action==$a)][0].status')" "PENDING"

CS_LAB=$(latest_snapshot LAB CANDIDATE)
post "/api/snapshots/$CS_LAB/confirm" "{}" "$OP1" >/dev/null
check "未签发检验报告随确认快照改归属" "$(lab_field P-A-002 masterId)" "$M3"

# 发现误并 → 拆分
V=$(version)
R=$(post /api/resolutions/split "{\"masterId\":\"$M3\",\"splitOutIdentityIds\":[\"$B2\"],\"expectedGraphVersion\":$V,\"reason\":\"复核发现误并，拆分陈晨B\"}" "$OP1")
M4=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
RID2S=$(echo "$R" | jq -r '.id')
check "拆分后陈晨B 进入新主体" "$(resolve_master REG_B P-B-002)" "$M4"
check "拆分后陈晨A 留在原合并主体" "$(resolve_master REG_A P-A-002)" "$M3"
check_ne "拆分后两陈晨不再同主体" "$(resolve_master REG_B P-B-002)" "$(resolve_master REG_A P-A-002)"
check "初始快照不回写：SN-INIT-APPT 仍解析到原单例主体" "$(snap_resolve "$SN_APPT" REG_B P-B-002)" "$M_B2_ORIG"
check "拆分生成新的提醒切换影响" "$(impact_count "$RID2S" APPOINTMENT REMIND_SWITCH)" "1"

CS_APPT2=$(latest_snapshot APPOINTMENT CANDIDATE)
post "/api/snapshots/$CS_APPT2/confirm" "{}" "$OP1" >/dev/null
check "确认后预约归属回到陈晨B 自己的主体" "$(appt_field P-B-002 masterId)" "$M4"
check "提醒电话恢复为陈晨B 本人号码" "$(appt_field P-B-002 contactPhone)" "13711110002"

ADJ_ID=$(get "/api/impacts?resolutionId=$RID2" | jq -r '[.[] | select(.action=="ADJUSTMENT_LIST")][0].id')
post "/api/impacts/$ADJ_ID/ack" "{\"note\":\"财务已线下调整 2026-10 账期\"}" "$OP1" >/dev/null
check "调整清单人工确认后状态 ACKED" \
  "$(get "/api/impacts?resolutionId=$RID2" | jq -r '[.[] | select(.action=="ADJUSTMENT_LIST")][0].status')" "ACKED"

echo
echo "【场景3】来源证件更正（赵六检验系统证件号末位录错）"
check "更正前 SN-INIT-LAB 中赵六检验身份为单例主体" "$(snap_resolve "$SN_LAB" LAB_A L-A-001)" "$M_L1_ORIG"
post "/api/source-identities/$L1/corrections" \
  "{\"field\":\"idCardNo\",\"newValue\":\"110101197703031111\",\"note\":\"检验系统更正证件号末位\"}" "$OP1" >/dev/null
check "旧证据候选对已作废（SUPERSEDED）" \
  "$(get /api/candidates | jq '[.[] | select(.status=="SUPERSEDED")] | length')" "2"
C_ZL=$(find_candidate "$A4" "$L1")
check "更正后生成 1.0 分新候选对" \
  "$(get "/api/candidates/$C_ZL" | jq -r '.score')" "1"
check "已发布快照不回写：SN-INIT-LAB 仍解析到原主体" "$(snap_resolve "$SN_LAB" LAB_A L-A-001)" "$M_L1_ORIG"

V=$(version)
R=$(post /api/resolutions/merge "{\"candidateId\":\"$C_ZL\",\"expectedGraphVersion\":$V,\"reason\":\"证件更正后确认同一就诊人\"}" "$OP1")
M5=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
RID3=$(echo "$R" | jq -r '.id')
check "赵六挂号与检验身份合并" "$(resolve_master LAB_A L-A-001)" "$M5"
check "影响分析生成检验候选快照" "$(impact_count "$RID3" LAB REATTRIBUTE)" "1"
CS_LAB3=$(latest_snapshot LAB CANDIDATE)
post "/api/snapshots/$CS_LAB3/confirm" "{}" "$OP1" >/dev/null
check "检验报告（尿常规）归属更新到合并主体" "$(lab_field L-A-001 masterId)" "$M5"

echo
echo "【场景4】并发决议：先提交者锁定，后提交者必须重新检查"
C_ZS1=$(find_candidate "$A4" "$S1")
C_FAM=$(find_candidate "$A3" "$B3")
V=$(version)
R=$(post /api/resolutions/merge "{\"candidateId\":\"$C_ZS1\",\"expectedGraphVersion\":$V,\"reason\":\"结算系统赵六并入\"}" "$OP1")
M6=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
check "操作员1 合并赵六结算身份成功（版本 $((V+1))）" "$(echo "$R" | jq -r '.graphVersion')" "$((V+1))"

CODE=$(post_status /api/resolutions/merge "{\"candidateId\":\"$C_FAM\",\"expectedGraphVersion\":$V,\"reason\":\"操作员2 基于过期版本提交\"}" "$OP2")
check "操作员2 持过期版本提交被拒绝（409）" "$CODE" "409"
check "409 响应携带当前图谱版本" "$(jq -r '.currentGraphVersion' /tmp/mpi-verify-body.json)" "$((V+1))"

CODE=$(post_status /api/resolutions/merge "{\"candidateId\":\"$C_ZS1\",\"expectedGraphVersion\":$V,\"reason\":\"操作员2 重复处理同一候选对\"}" "$OP2")
check "同一候选对被重复提交同样 409" "$CODE" "409"

V=$(version)
R=$(post "/api/candidates/$C_FAM/reject" "{\"reason\":\"家庭共用号码，非同一就诊人\"}" "$OP2")
check "操作员2 重新检查后拒绝家庭共用号码候选" "$(echo "$R" | jq -r '.status')" "REJECTED"
check_ne "李四与李四中仍是不同主体" "$(resolve_master REG_A P-A-003)" "$(resolve_master REG_B P-B-003)"
check "李四中预约电话未被切换" "$(appt_field P-B-003 contactPhone)" "13900000003"

CS_SETTLE4=$(latest_snapshot SETTLEMENT CANDIDATE)
post "/api/snapshots/$CS_SETTLE4/confirm" "{}" "$OP1" >/dev/null
check "未关账结算单随确认快照更新归属" "$(settle_field S-A-001 masterId)" "$M6"
CS_LAB4=$(latest_snapshot LAB CANDIDATE)
[ -n "$CS_LAB4" ] && post "/api/snapshots/$CS_LAB4/confirm" "{}" "$OP1" >/dev/null || true
check "检验报告归属最终主体" "$(lab_field L-A-001 masterId)" "$M6"

echo
echo "【场景5】历史可复现：按版本解析关系链"
check "v0 时赵六检验身份为单例主体" "$(get "/api/resolve?sourceSystem=LAB_A&sourcePatientId=L-A-001&atVersion=0" | jq -r '.masterId')" "$M_L1_ORIG"
check "当前版本赵六三身份同主体" "$(resolve_master SETTLE_A S-A-001)" "$M6"
check "决议链完整（4 合并 + 1 拆分）" "$(get /api/resolutions | jq '[.[] | select(.type=="MERGE")] | length')" "4"
check "拆分决议数" "$(get /api/resolutions | jq '[.[] | select(.type=="SPLIT")] | length')" "1"

echo
echo "【场景6】渐进式来源接入：简写姓名首批 → 补充出生年月 → 稳定性与暂存隔离"
SRC=CLINIC_X
# 锚点：三要素齐全的正式身份（供补充批次强证据自动链接）
post /api/source-identities \
  '{"sourceSystem":"CLINIC_X","sourcePatientId":"ANCHOR-1","fullName":"张三","phone":"13955550001","gender":"M","birthDate":"1990-01-01"}' >/dev/null

# 6.1 首批：旧病历只有病历号+简写姓名（中文列名别名），无出生年月
B1JSON=$(jq -n --arg s "$SRC" '{sourceSystem:$s,fileName:"legacy.csv",contractVersion:"legacy-v1",
  columns:["病历号","姓名","性别"],
  rows:[{"病历号":"MRN-B1","姓名":"张","性别":"M"},{"病历号":"MRN-B2","姓名":"李","性别":"M"}]}')
B1=$(post /api/batches "$B1JSON"); B1ID=$(echo "$B1" | jq -r .id)
check "首批状态 ACCEPTED（保守门槛，无自动链接）" "$(echo "$B1" | jq -r .status)" "ACCEPTED"
check "首批可确定链接=0" "$(echo "$B1" | jq -r .confirmedCount)" "0"
check "首批证据不足暂不链接=2" "$(echo "$B1" | jq -r .insufficientCount)" "2"
check "首批暂存记录数=2" "$(echo "$B1" | jq -r .stagedCount)" "2"
check "首批出生年月覆盖率 0%" "$(echo "$B1" | jq -r '.fieldCoverageJson|fromjson|.birthDate.coverageRate')" "0"
check "首批 MRN-B1 决策 INSUFFICIENT_STAGED" \
  "$(get "/api/batches/$B1ID/records" | jq -r '.[]|select(.sourcePatientId=="MRN-B1")|.decision')" "INSUFFICIENT_STAGED"
check "受限工作区有 2 条生效暂存" \
  "$(get /api/staging/active | jq --arg s "$SRC" '[.[]|select(.sourceSystem==$s)]|length')" "2"
# 中文关键字经 @uri 编码，避免未编码字符被容器判 400
KW=$(jq -rn --arg x 张 '$x|@uri')
check "受限查询强制范围提示 RESTRICTED_QUERY_ONLY" \
  "$(get "/api/staging/restricted-query?sourceSystem=$SRC&nameKeyword=$KW" | jq -r '.[0].scope')" "RESTRICTED_QUERY_ONLY"
check "受限查询强制临时性警告" \
  "$(get "/api/staging/restricted-query?sourceSystem=$SRC&nameKeyword=$KW" | jq -r '.[0].warning|contains("不得用于结算")')" "true"

# 6.2 同文件重传：指纹幂等
B1DUP=$(post /api/batches "$B1JSON")
check "同指纹重传返回同一批次" "$(echo "$B1DUP" | jq -r .id)" "$B1ID"
check "重传次数累计为 1" "$(echo "$B1DUP" | jq -r .reuploadCount)" "1"

# 6.3 第二批补齐出生年月：MRN-B1 与锚点三要素一致 → UPGRADED；MRN-B2 无候选 → STABLE
B2JSON=$(jq -n --arg s "$SRC" '{sourceSystem:$s,fileName:"enriched.csv",contractVersion:"enriched-v2",
  columns:["病历号","姓名","手机号","性别","出生年月"],
  rows:[{"病历号":"MRN-B1","姓名":"张三","手机号":"13955550001","性别":"M","出生年月":"1990-01-01"},
        {"病历号":"MRN-B2","姓名":"李四","手机号":"13955550002","性别":"M","出生年月":"1985-05-05"}]}')
B2=$(post /api/batches "$B2JSON"); B2ID=$(echo "$B2" | jq -r .id)
check "补充批次状态 ACCEPTED" "$(echo "$B2" | jq -r .status)" "ACCEPTED"
check "MRN-B1 稳定性 UPGRADED（暂存升级正式关联）" \
  "$(get "/api/batches/$B2ID/stability" | jq -r '.[]|select(.sourcePatientId=="MRN-B1")|.verdict')" "UPGRADED"
check "MRN-B2 稳定性 STABLE（首批决策不被回溯）" \
  "$(get "/api/batches/$B2ID/stability" | jq -r '.[]|select(.sourcePatientId=="MRN-B2")|.verdict')" "STABLE"
check "首批报告不被回溯改写（仍 2 条暂不链接）" \
  "$(get "/api/batches/$B1ID" | jq -r .insufficientCount)" "2"
check "补充后仅 MRN-B2 仍处暂存" \
  "$(get /api/staging/active | jq --arg s "$SRC" '[.[]|select(.sourceSystem==$s)]|length')" "1"
check "批次比较接口输出三类比例" \
  "$(get "/api/batches/compare?batchAId=$B1ID&batchBId=$B2ID" | jq -r '.batchA.confirmedLink // "x"')" "0"

# 6.4 列名漂移：受限待修复，不沿用旧解析器放行
DRIFT=$(post /api/batches "$(jq -n --arg s "$SRC" '{sourceSystem:$s,contractVersion:"legacy-v1",
  columns:["old_mrn","病人简写"],rows:[{"old_mrn":"DRIFT-1","病人简写":"赵"}]}')")
check "列名漂移批次 RESTRICTED" "$(echo "$DRIFT" | jq -r .status)" "RESTRICTED"
check "质量问题含 CONTRACT_DRIFT" "$(echo "$DRIFT" | jq -r '.qualityIssuesJson|contains("CONTRACT_DRIFT")')" "true"
check "漂移记录未产生身份" \
  "$(get /api/source-identities | jq -r '[.[]|select(.sourcePatientId=="DRIFT-1")]|length')" "0"
# 修复后新指纹重传，登记修复关系
FIXED=$(post /api/batches "$(jq -n --arg s "$SRC" --arg code "$(echo "$DRIFT"|jq -r .batchCode)" \
  '{sourceSystem:$s,contractVersion:"legacy-v1",repairOfBatchCode:$code,
    columns:["病历号","姓名"],rows:[{"病历号":"DRIFT-1","姓名":"赵"}]}')")
check "修复重传 ACCEPTED 且登记 repairOfBatchId" \
  "$(echo "$FIXED" | jq -r '.status + "|" + (.repairOfBatchId!=null|tostring)')" "ACCEPTED|true"

# 6.5 出生年月批量缺失（enriched-v2 三行空两行）：受限，不以空值填补
MISS=$(post /api/batches "$(jq -n --arg s "$SRC" '{sourceSystem:$s,contractVersion:"enriched-v2",
  columns:["病历号","姓名","出生年月"],
  rows:[{"病历号":"M-1","姓名":"孙一","出生年月":""},
        {"病历号":"M-2","姓名":"孙二","出生年月":""},
        {"病历号":"M-3","姓名":"孙三","出生年月":"1990-01-01"}]}')")
check "批量缺失批次 RESTRICTED" "$(echo "$MISS" | jq -r .status)" "RESTRICTED"
check "质量问题含 MISSING_BATCH_FIELD" "$(echo "$MISS" | jq -r '.qualityIssuesJson|contains("MISSING_BATCH_FIELD")')" "true"

# 6.6 更正日志与首批矛盾：受限，不静默覆盖首批事实
post /api/batches "$(jq -n --arg s "$SRC" '{sourceSystem:$s,contractVersion:"legacy-v1",
  columns:["病历号","姓名","证件号"],
  rows:[{"病历号":"CONTR-1","姓名":"吴九","证件号":"110101197703031111"}]}')" >/dev/null
CONTR=$(post /api/batches "$(jq -n --arg s "$SRC" '{sourceSystem:$s,contractVersion:"legacy-v1",
  columns:["病历号","姓名","证件号"],
  rows:[{"病历号":"CONTR-1","姓名":"吴九","证件号":"110101197703031112"}]}')")
check "更正矛盾批次 RESTRICTED" "$(echo "$CONTR" | jq -r .status)" "RESTRICTED"
check "质量问题含 CORRECTION_CONTRADICTION" "$(echo "$CONTR" | jq -r '.qualityIssuesJson|contains("CORRECTION_CONTRADICTION")')" "true"
check "首批证件号事实未被覆盖" \
  "$(get /api/source-identities | jq -r '.[]|select(.sourcePatientId=="CONTR-1")|.idCardNo')" "110101197703031111"

# 6.7 补充资料推翻早期弱候选
post /api/source-identities \
  '{"sourceSystem":"CLINIC_X","sourcePatientId":"W-ANCHOR","fullName":"王五","phone":"13700007777","gender":"M","birthDate":"1985-05-05"}' >/dev/null
W1=$(post /api/batches "$(jq -n --arg s "$SRC" '{sourceSystem:$s,contractVersion:"legacy-v1",
  columns:["病历号","姓名","手机号","性别"],
  rows:[{"病历号":"W-1","姓名":"王","手机号":"13700007777","性别":"M"}]}')")
check "首批弱证据仅人工审阅（无自动链接资格）" \
  "$(get "/api/batches/$(echo "$W1"|jq -r .id)/records" | jq -r '.[]|select(.sourcePatientId=="W-1")|.decision')" "MANUAL_REVIEW"
WPID=$(get "/api/batches/$(echo "$W1"|jq -r .id)/records" | jq -r '.[]|select(.sourcePatientId=="W-1")|.candidatePairId')
check "弱候选 autoLinkEligible=false" "$(get "/api/candidates/$WPID" | jq -r .autoLinkEligible)" "false"
W2=$(post /api/batches "$(jq -n --arg s "$SRC" '{sourceSystem:$s,contractVersion:"enriched-v2",
  columns:["病历号","姓名","手机号","性别","出生年月"],
  rows:[{"病历号":"W-1","姓名":"王","手机号":"13700007777","性别":"M","出生年月":"1992-02-02"}]}')")
check "补充矛盾出生年月 → 稳定性 OVERTURNED" \
  "$(get "/api/batches/$(echo "$W2"|jq -r .id)/stability" | jq -r '.[0].verdict')" "OVERTURNED"
check "被推翻候选状态 SUPERSEDED" "$(get "/api/candidates/$WPID" | jq -r .status)" "SUPERSEDED"

# 6.8 暂存隔离：弱证据不能发布为正式关联，且不流入快照/预约
SID=$(get /api/staging/active | jq -r --arg s "$SRC" '.[]|select(.sourceSystem==$s and .sourcePatientId=="MRN-B2")|.id')
V=$(version)
CODE=$(post_status "/api/staging/$SID/publish" "{\"expectedGraphVersion\":$V}" "$OP1")
check "无强证据发布暂存被拒（409）" "$CODE" "409"
SNAP=$(post /api/snapshots '{"businessType":"APPOINTMENT","label":"批次隔离验证快照"}' "$OP1")
SNAPID=$(echo "$SNAP" | jq -r .id)
FORMAL=$(get "/api/staging/active" | jq -r --arg s "$SRC" '.[]|select(.id=="'$SID'")|.formalMasterId')
check "正式快照可解析暂存身份（取正式主体，不取临时猜测）" \
  "$(get "/api/snapshots/$SNAPID/resolve?sourceSystem=$SRC&sourcePatientId=MRN-B2" | jq -r .masterId)" "$FORMAL"
check "快照物化数等于正式身份总数（无暂存专有映射）" \
  "$(get "/api/snapshots/$SNAPID/mappings" | jq 'length')" "$(get /api/source-identities | jq 'length')"
APPT=$(post /api/biz/appointments \
  "$(jq -n --arg snap "$SNAPID" --arg s "$SRC" '{snapshotId:$snap,sourceSystem:$s,sourcePatientId:"MRN-B2",apptTime:"2026-12-01T09:00:00Z"}')")
check "预约经正式快照建单，归属正式主体" "$(echo "$APPT" | jq -r .masterId)" "$FORMAL"
check "暂存仍为 ACTIVE，未流入任何正式业务表" \
  "$(get "/api/staging/active" | jq -r --arg id "$SID" '[.[]|select(.id==$id)]|length')" "1"

echo
echo "=========================================================="
echo " 验收回放完成：通过 $pass 项，失败 $fail 项"
echo "=========================================================="
[ "$fail" -eq 0 ]
