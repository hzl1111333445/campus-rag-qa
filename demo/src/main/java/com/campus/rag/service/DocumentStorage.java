package com.campus.rag.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/** 只管一件事：把字节落到磁盘。目录来自 application.yml 的 campus.upload-dir。 */
@Service
public class DocumentStorage {

    private final Path dir;

    public DocumentStorage(@Value("${campus.upload-dir}") String uploadDir) throws IOException {
        this.dir = Paths.get(uploadDir);
        Files.createDirectories(dir);   // 目录不存在就先建，否则第一次上传直接崩
    }

    /** 存文件，返回磁盘上的文件名。 */
    public String save(String originalName, byte[] bytes) throws IOException {
        String ext = "";
        int dot = originalName == null ? -1 : originalName.lastIndexOf('.');
        if (dot >= 0) {
            ext = originalName.substring(dot);
        }
        String storedName = UUID.randomUUID() + ext;
        Files.write(dir.resolve(storedName), bytes);
        return storedName;
    }

    public Path dir() {
        return dir;
    }

    /** 把磁盘上的文件读回来（"重新处理"时用：不用用户再传一次）。 */
    public byte[] read(String storedName) throws IOException {
        Path p = dir.resolve(storedName);
        if (!Files.exists(p)) {
            throw new IOException("磁盘上找不到文件：" + p);
        }
        return Files.readAllBytes(p);
    }
}