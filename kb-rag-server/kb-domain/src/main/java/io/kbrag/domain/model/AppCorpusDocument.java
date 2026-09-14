package io.kbrag.domain.model;

/** 发布语料对比所需的最小文档版本元数据，不包含正文或对象存储地址。 */
public record AppCorpusDocument(String versionId, String docId, String fileName, String version) { }
