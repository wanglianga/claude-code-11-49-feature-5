package com.clinic.mpi.service.ingest;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 文件指纹：对来源系统 + 规范化后的行内容（列名排序、JSON 稳定序列化）计算 SHA-256。
 * 同指纹重传由批次模块幂等处理；修复后的文件内容必然产生新指纹，作为新批次接入。
 */
@Component
public class FileFingerprinter {

    public String fingerprint(String sourceSystem, List<String> rawColumns,
                              List<Map<String, String>> rows) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("src=").append(sourceSystem).append('\n');
        canonical.append("cols=").append(rawColumns.stream().sorted().toList()).append('\n');
        for (Map<String, String> row : rows) {
            // TreeMap 保证键顺序稳定；显式 null 也参与摘要，防止"删字段"撞指纹
            canonical.append(new TreeMap<>(row)).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
