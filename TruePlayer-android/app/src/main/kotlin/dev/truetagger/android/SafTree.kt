package dev.truetagger.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.truetagger.model.TrackKeys

/**
 * Acesso à pasta de músicas escolhida pelo usuário (Storage Access Framework).
 * Todas as operações são síncronas: chame fora da thread principal.
 */
class SafTree(context: Context, val treeUri: Uri) {
    private val resolver = context.applicationContext.contentResolver

    data class Node(
        val docId: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val lastModified: Long,
    ) {
        val isDir: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    /** Arquivo encontrado por [walk]; [relPath] é relativo à pasta escolhida. */
    data class Entry(val relPath: String, val node: Node, val parentDocId: String)

    val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /** Nome de um documento. Uma consulta só: listar a pasta para achar um nome custa uma linha por arquivo dela. */
    fun displayName(docId: String): String? = runCatching {
        resolver.query(docUri(docId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun children(parentDocId: String): List<Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<Node>()
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                result += Node(
                    docId = c.getString(0).orEmpty(),
                    name = c.getString(1).orEmpty(),
                    mimeType = c.getString(2).orEmpty(),
                    sizeBytes = if (c.isNull(3)) 0L else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return result
    }

    fun child(parentDocId: String, name: String): Node? {
        val all = children(parentDocId)
        return all.firstOrNull { it.name == name } ?: all.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** Devolve o docId da pasta, criando-a se ainda não existir. */
    fun ensureDir(parentDocId: String, name: String): String? {
        child(parentDocId, name)?.let { return if (it.isDir) it.docId else null }
        return create(parentDocId, DocumentsContract.Document.MIME_TYPE_DIR, name)
    }

    /** Cria (ou localiza) a pasta em um caminho relativo como "songs/Artista/Album". */
    fun ensureDirPath(relDir: String): String? {
        var current = rootDocId
        for (part in TrackKeys.normalize(relDir).split('/').filter { it.isNotEmpty() }) {
            current = ensureDir(current, part) ?: return null
        }
        return current
    }

    fun create(parentDocId: String, mimeType: String, name: String): String? = runCatching {
        DocumentsContract.createDocument(resolver, docUri(parentDocId), mimeType, name)
            ?.let { DocumentsContract.getDocumentId(it) }
    }.getOrNull()

    fun readText(docId: String): String? = runCatching {
        resolver.openInputStream(docUri(docId))?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()

    fun writeText(docId: String, text: String): Boolean = runCatching {
        resolver.openOutputStream(docUri(docId), "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)

    /** Escreve [text] em `<pasta>/<name>`, criando o arquivo se necessário. Devolve o docId. */
    fun writeTextFile(parentDocId: String, name: String, text: String, mimeType: String = "text/plain"): String? {
        val docId = child(parentDocId, name)?.docId ?: create(parentDocId, mimeType, name) ?: return null
        return if (writeText(docId, text)) docId else null
    }

    /** Move sem copiar bytes (renomeação no mesmo provedor). Devolve o novo docId ou null se não foi possível. */
    fun move(docId: String, fromParentDocId: String, toParentDocId: String): String? = runCatching {
        DocumentsContract.moveDocument(resolver, docUri(docId), docUri(fromParentDocId), docUri(toParentDocId))
            ?.let { DocumentsContract.getDocumentId(it) }
    }.getOrNull()

    fun rename(docId: String, newName: String): String? = runCatching {
        DocumentsContract.renameDocument(resolver, docUri(docId), newName)
            ?.let { DocumentsContract.getDocumentId(it) }
    }.getOrNull()

    /** Só use em pastas vazias criadas pelo próprio app. */
    fun deleteEmptyDir(docId: String): Boolean {
        if (children(docId).isNotEmpty()) return false
        return runCatching { DocumentsContract.deleteDocument(resolver, docUri(docId)) }.getOrDefault(false)
    }

    /**
     * Lista todos os arquivos da pasta (recursivo). Pastas cujo nome (no nível indicado em
     * [skipDirs], comparado sem caixa) coincida são ignoradas por inteiro — use para `.git`.
     */
    fun walk(skipDirs: Set<String> = emptySet(), startDocId: String = rootDocId): List<Entry> {
        val skip = skipDirs.mapTo(HashSet()) { it.lowercase() }
        val out = ArrayList<Entry>()
        fun visit(dirDocId: String, prefix: String) {
            for (node in children(dirDocId)) {
                val rel = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
                if (node.isDir) {
                    if (node.name.lowercase() !in skip) visit(node.docId, rel)
                } else {
                    out += Entry(rel, node, dirDocId)
                }
            }
        }
        visit(startDocId, "")
        return out
    }

    /** Caminho do volume (ex.: "Music/TruePlayer/") para a pasta escolhida, ou "" se não der para saber. */
    fun rootRelativePath(): String {
        val path = rootDocId.substringAfter(':', "").trim('/')
        return if (path.isEmpty()) "" else "$path/"
    }

    /** Caminho absoluto só para volume primário (usado para pedir scan ao MediaStore). */
    fun absolutePath(relPath: String): String? {
        if (!rootDocId.startsWith("primary:")) return null
        val root = rootRelativePath()
        return "/storage/emulated/0/$root${TrackKeys.normalize(relPath)}"
    }
}
