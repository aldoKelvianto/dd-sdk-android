/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.sessionreplay.internal.recorder

import android.view.View
import android.view.ViewGroup
import com.datadog.android.sessionreplay.model.MobileSegment
import java.util.LinkedList

internal class SnapshotProducer(
    private val treeViewTraversal: TreeViewTraversal
) {

    fun produce(
        rootView: View,
        systemInformation: SystemInformation
    ): Node? {
        return convertViewToNode(rootView, systemInformation, LinkedList())
    }

    @Suppress("ComplexMethod", "ReturnCount")
    private fun convertViewToNode(
        view: View,
        systemInformation: SystemInformation,
        parents: LinkedList<MobileSegment.Wireframe>
    ): Node? {
        val traversedTreeView = treeViewTraversal.traverse(view, systemInformation)
        val nextTraversalStrategy = traversedTreeView.nextActionStrategy
        val resolvedWireframes = traversedTreeView.mappedWireframes
        if (nextTraversalStrategy == TreeViewTraversal.TraversalStrategy.STOP_AND_DROP_NODE) {
            return null
        }
        if (nextTraversalStrategy == TreeViewTraversal.TraversalStrategy.STOP_AND_RETURN_NODE) {
            return Node(wireframes = resolvedWireframes, parents = parents)
        }

        val childNodes = LinkedList<Node>()
        if (view is ViewGroup &&
            view.childCount > 0 &&
            nextTraversalStrategy == TreeViewTraversal.TraversalStrategy.TRAVERSE_ALL_CHILDREN
        ) {
            val parentsCopy = LinkedList(parents).apply { addAll(resolvedWireframes) }
            for (i in 0 until view.childCount) {
                val viewChild = view.getChildAt(i) ?: continue
                convertViewToNode(viewChild, systemInformation, parentsCopy)?.let {
                    childNodes.add(it)
                }
            }
        }
        return Node(
            children = childNodes,
            wireframes = resolvedWireframes,
            parents = parents
        )
    }
}
