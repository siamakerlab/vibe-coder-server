package com.siamakerlab.vibecoder.server.db

import org.jetbrains.exposed.sql.Table

object Devices : Table("devices") {
    val id = varchar("id", 64)
    val name = varchar("name", 128)
    val tokenHash = varchar("token_hash", 128)
    val createdAt = varchar("created_at", 64)
    val lastSeenAt = varchar("last_seen_at", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Projects : Table("projects") {
    val id = varchar("id", 64)
    val name = varchar("name", 256)
    val packageName = varchar("package_name", 256)
    val sourcePath = text("source_path")
    val moduleName = varchar("module_name", 128)
    val debugTask = varchar("debug_task", 128)
    val createdAt = varchar("created_at", 64)
    val updatedAt = varchar("updated_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object Builds : Table("builds") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).references(Projects.id)
    val variant = varchar("variant", 32)
    val status = varchar("status", 32)
    val logPath = text("log_path").nullable()
    val artifactId = varchar("artifact_id", 64).nullable()
    val errorMessage = text("error_message").nullable()
    val startedAt = varchar("started_at", 64).nullable()
    val finishedAt = varchar("finished_at", 64).nullable()
    val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object Artifacts : Table("artifacts") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).references(Projects.id)
    val buildId = varchar("build_id", 64)
    val type = varchar("type", 32)
    val fileName = varchar("file_name", 256)
    val filePath = text("file_path")
    val sizeBytes = long("size_bytes")
    val sha256 = varchar("sha256", 128)
    val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object UploadedFiles : Table("uploaded_files") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).references(Projects.id)
    val originalName = text("original_name")
    val filePath = text("file_path")
    val mimeType = varchar("mime_type", 128).nullable()
    val sizeBytes = long("size_bytes")
    val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}

val AllTables = arrayOf(Devices, Projects, Builds, Artifacts, UploadedFiles)
