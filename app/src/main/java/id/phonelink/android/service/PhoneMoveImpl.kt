package id.phonelink.android.service

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import id.phonelink.android.core.MoveItem
import id.phonelink.android.core.MoveKind
import id.phonelink.android.core.MoveSource
import id.phonelink.android.core.VCard
import id.phonelink.android.core.VCardContact
import java.io.File
import java.io.InputStream

/** What the user picked in the move wizard. Dates in ms since 1970; null is open-ended. */
data class MoveChoices(
    val contacts: Boolean = true,
    val media: Boolean = true,
    val videos: Boolean = true,
    val mediaFrom: Long? = null,
    val mediaTo: Long? = null,
    /** Folders picked with the system folder picker (tree URIs, kept with a persistable grant). */
    val folders: List<Uri> = emptyList(),
)

/** Lists what the old phone moves, and reads it while it goes. */
object MoveSources {
    private const val MAX_FILES = 20_000

    fun canReadContacts(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Every item for [choices]. Blocking: reads the contacts, the gallery, and the folders. */
    fun collect(context: Context, choices: MoveChoices): List<MoveItem> {
        val items = ArrayList<MoveItem>()
        if (choices.contacts && canReadContacts(context)) contactsItem(context)?.let(items::add)
        if (choices.media && PhotoSync.canRead(context)) items += media(context, choices)
        choices.folders.forEach { tree -> items += folder(context, tree, MAX_FILES - items.size) }
        return items
    }

    /** All contacts with a name, number, or email, as one .vcf in the app's files. */
    private fun contactsItem(context: Context): MoveItem? {
        val contacts = readContacts(context)
        if (contacts.isEmpty()) return null
        val folder = File(context.filesDir, "move").apply { mkdirs() }
        val file = File(folder, "Contacts.vcf")
        file.writeText(VCard.write(contacts))
        return MoveItem("contacts", MoveKind.Contacts, "Contacts.vcf", file.length(), file.path)
    }

    fun readContacts(context: Context): List<VCardContact> {
        val data = ContactsContract.Data.CONTENT_URI
        val columns = arrayOf(
            ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1,
        )
        val types = listOf(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        )
        val where = "${ContactsContract.Data.MIMETYPE} IN (${types.joinToString(",") { "?" }})"
        val byId = LinkedHashMap<Long, VCardContact>()
        runCatching {
            context.contentResolver.query(data, columns, where, types.toTypedArray(), "${ContactsContract.Data.DISPLAY_NAME} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val contact = byId[id] ?: VCardContact(c.getString(1).orEmpty())
                    val value = c.getString(3)?.trim().orEmpty()
                    byId[id] = when (c.getString(2)) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (value.isEmpty() || value in contact.phones) contact else contact.copy(phones = contact.phones + value)
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (value.isEmpty() || value in contact.emails) contact else contact.copy(emails = contact.emails + value)
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> if (value.isEmpty()) contact else contact.copy(organization = value)
                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> if (value.isEmpty()) contact else contact.copy(note = value)
                        else -> contact
                    }
                }
            }
        }
        return byId.values.filter { it.name.isNotBlank() || it.phones.isNotEmpty() || it.emails.isNotEmpty() }
    }

    /** Photos (and videos) in the date range, oldest first, from the same table photo sync reads. */
    private fun media(context: Context, choices: MoveChoices): List<MoveItem> {
        val videos = choices.videos && PhotoSync.canReadVideos(context)
        val pending = if (Build.VERSION.SDK_INT >= 29) " AND ${MediaStore.MediaColumns.IS_PENDING} = 0" else ""
        val columns = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
        val where = "1 = 1" + PhotoSync.typeFilter(videos) + pending
        val order = "${MediaStore.Files.FileColumns.DATE_ADDED} ASC, ${MediaStore.Files.FileColumns._ID} ASC"
        val out = ArrayList<MoveItem>()
        runCatching {
            context.contentResolver.query(MediaStore.Files.getContentUri("external"), columns, where, null, order)?.use { c ->
                while (c.moveToNext() && out.size < MAX_FILES) {
                    val id = c.getLong(0)
                    val taken = if (c.isNull(6)) c.getLong(3) * 1000 else c.getLong(6)
                    if (choices.mediaFrom != null && taken < choices.mediaFrom) continue
                    if (choices.mediaTo != null && taken > choices.mediaTo) continue
                    val isVideo = c.getInt(5) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val size = c.getLong(2)
                    if (size <= 0) continue
                    out += MoveItem(
                        "m:$id", MoveKind.Media, PhotoSync.fileName(c.getString(1), id, c.getString(4)), size,
                        ContentUris.withAppendedId(base, id).toString(),
                    )
                }
            }
        }
        return out
    }

    /** A picked folder's files, its subfolders kept under its name. */
    private fun folder(context: Context, tree: Uri, max: Int): List<MoveItem> {
        if (max <= 0) return emptyList()
        val resolver = context.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return emptyList()
        val rootName = displayName(context, DocumentsContract.buildDocumentUriUsingTree(tree, rootId)) ?: "Files"
        val out = ArrayList<MoveItem>()
        val queue = ArrayDeque(listOf(rootId to rootName))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        while (queue.isNotEmpty() && out.size < max) {
            val (parent, path) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            runCatching {
                resolver.query(children, columns, null, null, null)?.use { c ->
                    while (c.moveToNext() && out.size < max) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        if (name.startsWith(".")) continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (path.count { it == '/' } < 5) queue.addLast(id to "$path/$name")
                            continue
                        }
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        out += MoveItem("f:${tree.hashCode()}:$id", MoveKind.File, name, size, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), folder = path)
                    }
                }
            }
        }
        return out
    }

    /** A picked folder's name. */
    fun treeName(context: Context, tree: Uri): String =
        runCatching { displayName(context, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))) }.getOrNull()
            ?: tree.lastPathSegment.orEmpty()

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

/** Reads what the move sends: content URIs from the gallery and picked folders, and the contacts file. */
class MoveSourceImpl(context: Context) : MoveSource {
    private val context = context.applicationContext

    override fun open(item: MoveItem): InputStream =
        if (item.source.startsWith("content:")) {
            context.contentResolver.openInputStream(Uri.parse(item.source)) ?: error("${item.name} is gone")
        } else {
            File(item.source).inputStream()
        }

    override fun length(item: MoveItem): Long =
        if (item.source.startsWith("content:")) {
            runCatching { context.contentResolver.openAssetFileDescriptor(Uri.parse(item.source), "r")?.use { it.length } }.getOrNull()
                ?.takeIf { it > 0 } ?: item.size
        } else {
            File(item.source).length()
        }
}
