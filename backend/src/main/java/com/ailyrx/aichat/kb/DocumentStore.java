package com.ailyrx.aichat.kb;

import java.util.List;

/**
 * 文档元数据存储抽象；后续可替换为数据库实现。
 */
public interface DocumentStore {

    DocumentRecord save(DocumentRecord document);

    List<DocumentRecord> findAll();
}
