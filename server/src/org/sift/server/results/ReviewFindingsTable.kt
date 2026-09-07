package org.sift.server.results

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object ReviewFindingsTable : Table("review_findings") {
    val id = long("id").autoIncrement()
    val resultId = uuid("result_id").references(ReviewResultsTable.id, onDelete = ReferenceOption.CASCADE)
    val file = text("file")
    val startLine = integer("start_line").nullable()
    val endLine = integer("end_line").nullable()
    val severity = text("severity")
    val category = text("category").nullable()
    val message = text("message")
    val suggestion = text("suggestion").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(customIndexName = "review_findings_result_severity", isUnique = false, resultId, severity)
        index(customIndexName = "review_findings_file", isUnique = false, file)
    }
}
