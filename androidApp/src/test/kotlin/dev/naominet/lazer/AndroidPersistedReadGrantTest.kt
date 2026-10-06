package dev.naominet.lazer

import android.provider.DocumentsContract
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidPersistedReadGrantTest {
    @Test
    fun treeGrantCoversDocumentsAddressedThroughTheSameTree() {
        val tree = DocumentsContract.buildTreeDocumentUri("music.provider", "primary:Music")!!
        val child = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            "primary:Music/Album/track.flac",
        )

        assertTrue(persistedReadGrantCovers(tree, child))
    }

    @Test
    fun treeGrantDoesNotCoverAnotherProviderOrAnotherTree() {
        val tree = DocumentsContract.buildTreeDocumentUri("music.provider", "primary:Music")!!
        val differentProviderChild = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri("other.provider", "primary:Music")!!,
            "primary:Music/track.flac",
        )
        val differentTree = DocumentsContract.buildTreeDocumentUri("music.provider", "primary:Other")!!
        val differentTreeChild = DocumentsContract.buildDocumentUriUsingTree(
            differentTree,
            "primary:Other/track.flac",
        )

        assertFalse(persistedReadGrantCovers(tree, differentProviderChild))
        assertFalse(persistedReadGrantCovers(tree, differentTreeChild))
    }

    @Test
    fun documentGrantRemainsExactUriOnly() {
        val document = DocumentsContract.buildDocumentUri("music.provider", "primary:Music/track.flac")
        val otherDocument = DocumentsContract.buildDocumentUri("music.provider", "primary:Music/other.flac")
        val treeScopedDocument = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri("music.provider", "primary:Music")!!,
            "primary:Music/track.flac",
        )

        assertTrue(persistedReadGrantCovers(document, document))
        assertFalse(persistedReadGrantCovers(document, otherDocument))
        assertFalse(persistedReadGrantCovers(document, treeScopedDocument))
    }

    @Test
    fun persistedTreeGrantIsKeptForLibraryRootsAndQueuedFilesOnly() {
        val tree = DocumentsContract.buildTreeDocumentUri("music.provider", "primary:Music")!!
        val child = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Music/track.flac")
        val unrelated = DocumentsContract.buildTreeDocumentUri("music.provider", "primary:Other")!!

        assertTrue(persistedReadGrantIsRequired(tree, listOf(tree), emptyList()))
        assertTrue(persistedReadGrantIsRequired(tree, emptyList(), listOf(child)))
        assertTrue(persistedReadGrantIsRequired(tree, emptyList(), emptyList(), setOf(tree.toString())))
        assertFalse(persistedReadGrantIsRequired(tree, emptyList(), emptyList()))
        assertFalse(persistedReadGrantIsRequired(unrelated, listOf(tree), listOf(child)))
    }
}
