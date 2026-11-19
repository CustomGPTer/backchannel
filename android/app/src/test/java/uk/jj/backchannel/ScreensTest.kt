package uk.jj.backchannel

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uk.jj.backchannel.core.Contact
import uk.jj.backchannel.core.Message
import uk.jj.backchannel.core.Node
import uk.jj.backchannel.data.SqlStorage
import uk.jj.backchannel.net.TorStatus
import uk.jj.backchannel.ui.AddContactScreen
import uk.jj.backchannel.ui.BackchannelTheme
import uk.jj.backchannel.ui.ChatScreen
import uk.jj.backchannel.ui.HomeScreen
import uk.jj.backchannel.ui.SettingsScreen
import uk.jj.backchannel.ui.SetupFlow
import uk.jj.backchannel.ui.UnlockScreen
import java.io.IOException

/**
 * Draws every screen on the computer, clicks through the setup steps, and saves a picture of
 * each one to app/build/shots so they can be looked at before the app goes on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var storage: SqlStorage
    private var node: Node? = null
    private var dave = 0L

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        storage = SqlStorage.open(app, null, FrameworkSQLiteOpenHelperFactory())
        node = Node(
            storage,
            transport = { throw IOException("no network in tests") },
            // As in the app: any change to stored data tells the screens to reload.
            events = object : Node.Events {
                override fun onChanged() { Runtime.changes.value = Runtime.changes.value + 1 }
            },
        ).also {
            it.selfOnion = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx"
            it.profileName = "Jon"
        }
        val now = System.currentTimeMillis()
        dave = storage.contactInsert("d".repeat(56), "Dave Hughes", false, Contact.ACTIVE, null, null, now - 86_400_000)
        storage.contactSeen(dave, now - 120_000)
        var n = 0
        fun msg(out: Boolean, text: String, status: Int, minsAgo: Int) =
            storage.messageInsert(dave, ByteArray(16) { (n++).toByte() }, out, Message.KIND_TEXT, text, now - minsAgo * 60_000L, status)
        msg(false, "Morning. Are you on site today?", Message.RECEIVED, 50)
        msg(true, "Yes, in from seven. Gate 3.", Message.DELIVERED, 48)
        msg(false, "Good. I'll bring the drawings over about ten.", Message.RECEIVED, 47)
        msg(true, "Sound. Bring the rev C set if you've got it, the one with the revised chamber levels.", Message.DELIVERED, 45)
        // A photo, a voice note and a timer change, to draw the newer kinds of message.
        val photo = samplePhoto()
        val photoId = storage.messageInsert(dave, ByteArray(16) { 40 }, false, Message.KIND_PHOTO, "Cover's cracked on MH12", now - 40 * 60_000L, Message.RECEIVED)!!
        storage.attachmentPut(photoId, 1200, 900, 0, photo)
        storage.messageInsert(dave, ByteArray(16) { 41 }, true, Message.KIND_NOTE, "3600", now - 30 * 60_000L, Message.DELIVERED)
        storage.contactSetTimer(dave, 3600)
        val voiceId = storage.messageInsert(dave, ByteArray(16) { 42 }, true, Message.KIND_VOICE, "", now - 20 * 60_000L, Message.DELIVERED, 3600)!!
        storage.attachmentPut(voiceId, 0, 0, 23_000, ByteArray(4000))
        storage.messageInsert(dave, ByteArray(16) { 43 }, true, Message.KIND_TEXT, "Also is the crane booked for Thursday?", now - 2 * 60_000L, Message.QUEUED, 3600)

        val priya = storage.contactInsert("p".repeat(56), "Priya", true, Contact.ACTIVE, null, null, now - 4000)
        storage.contactSetPhoto(priya, uk.jj.backchannel.media.Photos.avatar(android.graphics.BitmapFactory.decodeByteArray(photo, 0, photo.size)))
        storage.messageInsert(priya, ByteArray(16) { 1 }, false, Message.KIND_TEXT, "Can you ring me when you're free?", now - 600_000, Message.RECEIVED)
        storage.contactAddUnread(priya, 2)
        storage.contactInsert("s".repeat(56), "Sam", true, Contact.PENDING, null, ByteArray(16), now - 3000)
        storage.contactInsert("r".repeat(56), "New contact", false, Contact.REFUSED, null, null, now - 2000)
        storage.contactInsert("t".repeat(56), "Terry", true, Contact.ACTIVE, null, null, now - 1000)

        Runtime.attachForTests(app, storage, node)
        Runtime.tor.value = TorStatus(TorStatus.Phase.ONLINE, 100, reachable = true)
    }

    /** A made-up picture (sky, ground, a dark circle) standing in for a real photo. */
    private fun samplePhoto(): ByteArray {
        val w = 1200
        val h = 900
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.rgb(140, 170, 200); c.drawRect(0f, 0f, w.toFloat(), h * 0.45f, p)
        p.color = android.graphics.Color.rgb(120, 110, 95); c.drawRect(0f, h * 0.45f, w.toFloat(), h.toFloat(), p)
        p.color = android.graphics.Color.rgb(45, 45, 50); c.drawCircle(w * 0.5f, h * 0.68f, 190f, p)
        p.color = android.graphics.Color.rgb(90, 90, 95); c.drawCircle(w * 0.5f, h * 0.68f, 150f, p)
        return java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    @After fun tearDown() {
        node?.close()
        storage.close()
    }

    @Composable
    private fun Framed(dark: Boolean = true, content: @Composable () -> Unit) {
        BackchannelTheme(dark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/shots/$name.png")
    }

    private fun waitForText(text: String) =
        compose.waitUntil(8000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun setupWalksThroughEveryStep() {
        compose.setContent { Framed { SetupFlow() } }
        shot("01-welcome")
        compose.onNodeWithText("Get started").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Jon")
        shot("02-name")
        compose.onNodeWithText("Next").performClick()

        // A weak PIN must not be accepted.
        compose.onNode(hasSetTextAction()).performTextInput("123456")
        compose.onNodeWithText("Strength: Weak").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        shot("03-lock-weak-pin")
    }

    @Test fun setupAcceptsADecentPinAndShowsItsStrength() {
        compose.setContent { Framed { SetupFlow() } }
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("839471625")
        compose.onNodeWithText("Strength: Strong").assertExists()
        compose.onNodeWithText("Next").assertIsEnabled()
        shot("04-lock-strong-pin")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Type it again").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("839471620")
        compose.onNodeWithText("Finish").performClick()
        waitForText("That doesn't match")
        shot("05-confirm-mismatch")
    }

    @Test fun setupPassphraseOption() {
        compose.setContent { Framed { SetupFlow() } }
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Passphrase").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("correct horse battery staple")
        compose.onNodeWithText("Strength: Very strong").assertExists()
        shot("06-lock-passphrase")
    }

    @Test fun unlock() {
        compose.setContent { Framed { UnlockScreen() } }
        compose.onNodeWithText("Unlock").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("839471")
        compose.onNodeWithText("Unlock").assertIsEnabled()
        shot("07-unlock")
    }

    @Test fun home() {
        compose.setContent { Framed { HomeScreen({}, {}, {}) } }
        waitForText("Dave Hughes")
        compose.onNodeWithText("Waiting to reach their phone…").assertExists()
        compose.onNodeWithText("1 waiting").assertExists()
        compose.onNodeWithText("You: Also is the crane booked for Thursday?", substring = true).assertExists()
        shot("08-home")
    }

    @Test fun homeLight() {
        compose.setContent { Framed(dark = false) { HomeScreen({}, {}, {}) } }
        waitForText("Dave Hughes")
        shot("09-home-light")
    }

    @Test fun homeWhileConnecting() {
        Runtime.tor.value = TorStatus(TorStatus.Phase.CONNECTING, 45)
        compose.setContent { Framed { HomeScreen({}, {}, {}) } }
        waitForText("Connecting to Tor… 45%")
        shot("10-home-connecting")
    }

    @Test fun chat() {
        compose.setContent { Framed { ChatScreen(dave, onBack = {}) } }
        waitForText("crane booked")
        compose.onNodeWithText("Waiting for them to come online").assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Disappearing messages on")).assertExists()
        compose.onNodeWithText("1 hour").assertExists()
        compose.onNodeWithText("You set messages to disappear after 1 hour").assertExists()
        compose.onNodeWithText("Cover's cracked on MH12").assertExists()
        compose.waitUntil(8000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Photo. Tap to view.")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Play voice note")).assertExists()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Record a voice note")).assertExists()
        shot("11-chat")
    }

    @Test fun chatTimerChoices() {
        compose.setContent { Framed { ChatScreen(dave, onBack = {}) } }
        waitForText("crane booked")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("More")).performClick()
        compose.onNodeWithText("Name and picture").assertExists()
        compose.onNodeWithText("Delete contact").assertExists()
        compose.onNodeWithText("Disappearing messages").performClick()
        for (label in listOf("Off", "5 minutes", "1 hour", "8 hours", "1 day", "1 week", "4 weeks")) {
            // "1 hour" is on screen twice: in the list and as the chat's current timer.
            org.junit.Assert.assertTrue(label, compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty())
        }
        shot("16-timer-choices")
        // Choosing a new time changes the chat and leaves a note of it.
        compose.onNodeWithText("1 day").performClick()
        compose.onNodeWithText("Set").performClick()
        waitForText("You set messages to disappear after 1 day")
        org.junit.Assert.assertEquals(86_400, storage.contactById(dave)!!.timerS)
    }

    @Test fun typingSwapsTheMicrophoneForSend() {
        compose.setContent { Framed { ChatScreen(dave, onBack = {}) } }
        waitForText("crane booked")
        compose.onNode(hasSetTextAction()).performTextInput("On my way")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Send")).assertExists().performClick()
        waitForText("On my way")
        val sent = storage.messagesFor(dave, 100).last()
        org.junit.Assert.assertEquals("On my way", sent.body)
        org.junit.Assert.assertEquals("a new message takes the chat's timer", 3600, sent.expireS)
    }

    @Test fun photoPreviewBeforeSending() {
        val prepared = uk.jj.backchannel.media.Photos.prepare(samplePhoto())
        var sentCaption: String? = null
        compose.setContent {
            Framed {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    uk.jj.backchannel.ui.PhotoPreview(prepared, onCancel = {}, onSend = { sentCaption = it })
                }
            }
        }
        compose.onNodeWithText("Send this photo?").assertExists()
        compose.onNodeWithText("hidden information have been removed", substring = true).assertExists()
        shot("18-photo-preview")
        compose.onNode(hasSetTextAction()).performTextInput("MH12")
        compose.onNodeWithText("Send").performClick()
        org.junit.Assert.assertEquals("MH12", sentCaption)
    }

    @Test fun chatWaitingToConnect() {
        val sam = storage.contactByOnion("s".repeat(56))!!.id
        compose.setContent { Framed { ChatScreen(sam, onBack = {}) } }
        waitForText("swap keys")
        shot("12-chat-pending")
    }

    @Test fun addContactMyCode() {
        compose.setContent { Framed { AddContactScreen(onBack = {}, onAdded = {}) } }
        if (node != null) waitForText("Send invite")
        shot("13-add-my-code")
        compose.onNodeWithText("Add someone").performClick()
        compose.onNodeWithText("Scan their code").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("this is not an invite")
        compose.onNodeWithText("Add").performClick()
        waitForText("That isn't a Backchannel invite.")
        shot("14-add-someone")
    }

    @Test fun settings() {
        var opened = ""
        compose.setContent { Framed { SettingsScreen(onBack = {}, onLock = { opened = "lock" }, onBackup = { opened = "backup" }) } }
        waitForText("Your name")
        shot("15-settings")
        compose.onNodeWithText("Lock").performClick()
        org.junit.Assert.assertEquals("lock", opened)
        compose.onNodeWithText("Backup").performClick()
        org.junit.Assert.assertEquals("backup", opened)
        compose.onNodeWithText("Check now").assertExists()
        compose.onNodeWithText("Copy download page link").assertExists()
    }

    @Test fun settingsShowsWhenAnUpdateIsAvailable() {
        Runtime.update.value = Runtime.Update.Available("0.9")
        try {
            compose.setContent { Framed { SettingsScreen(onBack = {}) } }
            waitForText("Version 0.9 is available.")
            compose.onNodeWithText("Open download page").assertExists()
        } finally {
            Runtime.update.value = Runtime.Update.Unknown
        }
    }

    @Test fun homeShowsWhenAnUpdateIsAvailable() {
        Runtime.update.value = Runtime.Update.Available("0.9")
        try {
            compose.setContent { Framed { HomeScreen({}, {}, {}) } }
            waitForText("Version 0.9 is available")
        } finally {
            Runtime.update.value = Runtime.Update.Unknown
        }
    }

    @Test fun lockSettings() {
        compose.setContent { Framed { uk.jj.backchannel.ui.LockSettingsScreen(onBack = {}) } }
        waitForText("How strong each lock is")
        for (t in listOf("PIN, 6 digits", "Fair", "Passphrase, four or five words", "Very strong", "Speed, not strength")) {
            org.junit.Assert.assertTrue(t, compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty())
        }
        shot("19-lock-settings")
    }

    @Test fun backupScreenInsistsOnAStrongPassphrase() {
        compose.setContent { Framed { uk.jj.backchannel.ui.BackupScreen(onBack = {}) } }
        waitForText("Save a backup file")
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("gate7blue")
        compose.onNodeWithText("Choose where to save it").assertIsNotEnabled()
        compose.onNodeWithText("The passphrase needs to be rated Good or better.").assertExists()
        shot("20-backup-weak")
        fields[0].performTextInput(" horse battery staple")
        fields[1].performTextInput("gate7blue horse battery staple")
        compose.onNodeWithText("Choose where to save it").assertIsEnabled()
        shot("21-backup")
    }

    @Test fun setupOffersRestoreFromABackup() {
        compose.setContent { Framed { SetupFlow() } }
        compose.onNodeWithText("Restore from a backup file").assertExists()
    }
}
