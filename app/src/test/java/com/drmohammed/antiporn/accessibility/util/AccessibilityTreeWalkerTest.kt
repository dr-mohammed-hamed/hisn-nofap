package com.drmohammed.antiporn.accessibility.util

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTreeWalkerTest {

    private class FakeNode(
        val id: String,
        val sensitive: Boolean = false,
        val children: List<FakeNode> = emptyList(),
        var recycled: Boolean = false
    )

    @Test
    fun isSensitiveInputNode_returnsFalseForNull() {
        assertFalse(AccessibilityTreeWalker.isSensitiveInputNode(null))
    }

    @Test
    fun isSensitiveInput_classifiesPasswordsWithoutMisclassifyingUrlOrNormalFields() {
        assertTrue(AccessibilityTreeWalker.isSensitiveInput(isPassword = true, inputType = 0))
        assertTrue(
            AccessibilityTreeWalker.isSensitiveInput(
                isPassword = false,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
        )
        assertTrue(
            AccessibilityTreeWalker.isSensitiveInput(
                isPassword = false,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
        )
        assertTrue(
            AccessibilityTreeWalker.isSensitiveInput(
                isPassword = false,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )
        )
        assertTrue(
            AccessibilityTreeWalker.isSensitiveInput(
                isPassword = false,
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            )
        )

        assertFalse(
            AccessibilityTreeWalker.isSensitiveInput(
                isPassword = false,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            )
        )
        assertFalse(
            AccessibilityTreeWalker.isSensitiveInput(
                isPassword = false,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            )
        )
    }

    @Test
    fun extractSafeText_redactsPasswordAndJoinsNonBlankItems() {
        assertEquals(
            "",
            AccessibilityTreeWalker.extractSafeText(
                isPassword = true,
                texts = listOf("secret123", "extra")
            )
        )

        assertEquals(
            "Hello World",
            AccessibilityTreeWalker.extractSafeText(
                isPassword = false,
                texts = listOf(null, "   ", "Hello", "", "World", "\t\n")
            )
        )
        assertEquals("", AccessibilityTreeWalker.extractSafeText(isPassword = false, texts = null))
    }

    @Test
    fun traverseGeneric_respectsMaxDepthAndRecyclesOnlyChildren() {
        val grandChild = FakeNode("grandChild")
        val child = FakeNode("child", children = listOf(grandChild))
        val root = FakeNode("root", children = listOf(child))
        val visited = mutableListOf<String>()

        val stopped = AccessibilityTreeWalker.traverseGeneric(
            root = root,
            maxDepth = 1,
            maxNodes = 50,
            skipSensitiveNodes = true,
            isSensitive = { it.sensitive },
            childCountOf = { it.children.size },
            childAt = { node, idx -> node.children[idx] },
            recycle = { it.recycled = true },
            visitor = { node ->
                visited.add(node.id)
                false
            }
        )

        assertFalse(stopped)
        assertEquals(listOf("root", "child"), visited)
        assertFalse(root.recycled)
        assertTrue(child.recycled)
        assertFalse(grandChild.recycled)
    }

    @Test
    fun traverseGeneric_respectsMaxNodes() {
        val c1 = FakeNode("c1")
        val c2 = FakeNode("c2")
        val c3 = FakeNode("c3")
        val root = FakeNode("root", children = listOf(c1, c2, c3))
        val visited = mutableListOf<String>()

        val stopped = AccessibilityTreeWalker.traverseGeneric(
            root = root,
            maxDepth = 5,
            maxNodes = 2,
            skipSensitiveNodes = true,
            isSensitive = { it.sensitive },
            childCountOf = { it.children.size },
            childAt = { node, idx -> node.children[idx] },
            recycle = { it.recycled = true },
            visitor = { node ->
                visited.add(node.id)
                false
            }
        )

        assertFalse(stopped)
        assertEquals(listOf("root", "c1"), visited)
        assertFalse(root.recycled)
        assertTrue(c1.recycled)
        assertFalse(c2.recycled)
        assertFalse(c3.recycled)
    }

    @Test
    fun traverseGeneric_skipsSensitiveSubtreeWhenEnabledAndVisitsWhenDisabled() {
        val secretChild = FakeNode("secret_child")
        val secretParent = FakeNode("secret_parent", sensitive = true, children = listOf(secretChild))
        val publicNode = FakeNode("public")
        val root = FakeNode("root", children = listOf(secretParent, publicNode))

        val visitedSkipping = mutableListOf<String>()
        AccessibilityTreeWalker.traverseGeneric(
            root = root,
            skipSensitiveNodes = true,
            isSensitive = { it.sensitive },
            childCountOf = { it.children.size },
            childAt = { node, idx -> node.children[idx] },
            recycle = { it.recycled = true },
            visitor = { node ->
                visitedSkipping.add(node.id)
                false
            }
        )

        assertEquals(listOf("root", "public"), visitedSkipping)
        assertFalse(root.recycled)
        assertTrue(secretParent.recycled)
        assertFalse(secretChild.recycled)
        assertTrue(publicNode.recycled)

        // Reset recycle flags and verify skipSensitiveNodes = false visits everything
        secretParent.recycled = false
        publicNode.recycled = false
        val visitedAll = mutableListOf<String>()
        AccessibilityTreeWalker.traverseGeneric(
            root = root,
            skipSensitiveNodes = false,
            isSensitive = { it.sensitive },
            childCountOf = { it.children.size },
            childAt = { node, idx -> node.children[idx] },
            recycle = { it.recycled = true },
            visitor = { node ->
                visitedAll.add(node.id)
                false
            }
        )

        assertEquals(listOf("root", "secret_parent", "secret_child", "public"), visitedAll)
        assertFalse(root.recycled)
        assertTrue(secretParent.recycled)
        assertTrue(secretChild.recycled)
        assertTrue(publicNode.recycled)
    }

    @Test
    fun traverseGeneric_stopsEarlyAndRecyclesFetchedNodesWithoutRecyclingRoot() {
        val grandChild = FakeNode("target")
        val child1 = FakeNode("child1", children = listOf(grandChild))
        val child2 = FakeNode("child2")
        val root = FakeNode("root", children = listOf(child1, child2))
        val visited = mutableListOf<String>()

        val stopped = AccessibilityTreeWalker.traverseGeneric(
            root = root,
            skipSensitiveNodes = true,
            isSensitive = { it.sensitive },
            childCountOf = { it.children.size },
            childAt = { node, idx -> node.children[idx] },
            recycle = { it.recycled = true },
            visitor = { node ->
                visited.add(node.id)
                node.id == "target"
            }
        )

        assertTrue(stopped)
        assertEquals(listOf("root", "child1", "target"), visited)
        assertFalse(root.recycled)
        assertTrue(child1.recycled)
        assertTrue(grandChild.recycled)
        assertFalse(child2.recycled)
    }
}
