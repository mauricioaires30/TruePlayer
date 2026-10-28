package dev.truetagger.android

import dev.truetagger.data.CleanupRules
import dev.truetagger.data.MigrationPlanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Limpeza da pasta de músicas: acha o que não é música, playlist nem arquivo do app e move para
 * `_lixeira/` depois que o usuário confirma. Nada é apagado, e pasta com música dentro nunca entra
 * na lista (isso é trabalho da organização para songs/).
 */
class FolderOrganizer(private val tree: SafTree) {
    data class Candidate(
        val name: String,
        val isDir: Boolean,
        val files: Int,
        val sizeBytes: Long,
        /** Primeiros arquivos encontrados dentro, para o usuário reconhecer do que se trata. */
        val samples: List<String>,
        val docId: String,
    )

    data class Scan(val candidates: List<Candidate>, val foldersWithMusic: List<String>)

    data class Result(val moved: Int, val failed: List<String>)

    fun scan(): Scan {
        val candidates = ArrayList<Candidate>()
        val withMusic = ArrayList<String>()
        for (node in tree.children(tree.rootDocId).sortedBy { it.name.lowercase(Locale.ROOT) }) {
            if (CleanupRules.isProtectedName(node.name)) continue
            if (!node.isDir) {
                if (!CleanupRules.isMusicFile(node.name)) {
                    candidates += Candidate(node.name, false, 1, node.sizeBytes, emptyList(), node.docId)
                }
                continue
            }
            val inside = tree.walk(startDocId = node.docId)
            if (inside.any { CleanupRules.isMusicFile(it.node.name) }) {
                withMusic += node.name
            } else {
                candidates += Candidate(
                    name = node.name,
                    isDir = true,
                    files = inside.size,
                    sizeBytes = inside.sumOf { it.node.sizeBytes },
                    samples = inside.take(3).map { it.relPath },
                    docId = node.docId,
                )
            }
        }
        return Scan(candidates, withMusic)
    }

    /** Move cada item selecionado para `_lixeira/<data>/`. Cada item é independente: um erro não afeta os outros. */
    fun moveToTrash(selected: List<Candidate>): Result {
        if (selected.isEmpty()) return Result(0, emptyList())
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())
        val trashRoot = tree.ensureDir(tree.rootDocId, MigrationPlanner.TRASH_DIR)
            ?: return Result(0, selected.map { it.name })
        val trashDir = tree.ensureDir(trashRoot, stamp) ?: return Result(0, selected.map { it.name })
        var moved = 0
        val failed = ArrayList<String>()
        for (item in selected) {
            val ok = runCatching { moveOne(item, trashDir) }.getOrDefault(false)
            if (ok) moved++ else failed += item.name
        }
        return Result(moved, failed)
    }

    private fun moveOne(item: Candidate, trashDir: String): Boolean {
        // A pasta da data é nova, então só há conflito se dois itens tiverem o mesmo nome (impossível na raiz).
        val sameNameInTrash = tree.child(trashDir, item.name) != null
        var docId = item.docId
        if (sameNameInTrash) {
            val taken = (tree.children(trashDir) + tree.children(tree.rootDocId)).mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }
            docId = tree.rename(docId, CleanupRules.freeName(item.name, taken)) ?: return false
        }
        return tree.move(docId, tree.rootDocId, trashDir) != null
    }
}
