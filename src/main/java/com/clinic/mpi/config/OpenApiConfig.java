package com.clinic.mpi.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mpiOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("就诊人主索引（MPI）合并拆分与快照稳定服务")
                        .version("1.0.0")
                        .description("""
                                连锁诊所跨院区就诊人主索引服务。

                                * 来源身份接入：挂号 / 检验 / 结算等来源系统的就诊人身份登记与证件更正；
                                * 候选匹配：按姓名、手机号、证件号、出生日期评分生成候选重复对；
                                * 人工决议：合并 / 拆分均携带 `expectedGraphVersion`，先提交者锁定图谱，后提交者收到 409 必须重新检查；
                                * 时态关系图：身份→主体链接带生效版本区间，历史归属可按版本复现；
                                * 业务快照：预约、结算、检验、分析在启动时固定引用图谱版本；下游只读已发布快照；
                                * 影响清单：身份变化生成影响项与候选快照，各业务按自身规则领取；已关账结算只生成调整清单，不改写凭证；
                                * 渐进式来源接入：文件批次字段契约检测、覆盖与三类决策比例、强证据自动链接门槛、可逆暂存工作区（RESTRICTED_QUERY_ONLY）与补充批次稳定性评估，暂存归属结构上不进入正式图谱与业务快照。

                                操作员身份通过请求头 `X-Operator-Id` 传递（测试账号见 README：`op_zhang` / `op_li`）。
                                """))
                .addServersItem(new Server().url("/").description("当前服务"));
    }
}
