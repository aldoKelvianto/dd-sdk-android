/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.sessionreplay.internal.recorder

/**
 * Contains the context information which will be passed from parent to its children when
 * traversing the tree view for masking.
 */
data class MappingContext(
    val systemInformation: SystemInformation,
    val hasOptionSelectorParent: Boolean = false
)
