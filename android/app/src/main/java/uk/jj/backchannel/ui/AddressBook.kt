package uk.jj.backchannel.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import uk.jj.backchannel.media.Photos

class PickedContact(val name: String, val photo: ByteArray?)

/**
 * Reads the one address-book entry the user has just chosen in Android's own contact
 * picker. The app never asks for permission to read the address book: Android hands over
 * access to that single entry only. The name and a small copy of the picture are then kept
 * inside the encrypted database.
 */
fun readPickedContact(context: Context, uri: Uri): PickedContact? {
    val resolver = context.contentResolver
    val name = try {
        resolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    } ?: return null

    // The picture is a bonus: some phones don't hand it over with the entry.
    val photo = try {
        ContactsContract.Contacts.openContactPhotoInputStream(resolver, uri, false)?.use { stream ->
            BitmapFactory.decodeStream(stream)?.let { Photos.avatar(it) }
        }
    } catch (_: Exception) {
        null
    }
    return PickedContact(name.trim(), photo)
}
