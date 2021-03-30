/*
 * Copyright 2021 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.kotlin.kv.internal

import com.couchbase.client.kotlin.annotations.VolatileCouchbaseApi

@VolatileCouchbaseApi
public open class LookupInMacro internal constructor(internal val value: String) {
    public object Document : LookupInMacro("\$document")
    public object ExpiryTime : LookupInMacro("\$document.exptime")
    public object Cas : LookupInMacro("\$document.CAS")
    public object SeqNo : LookupInMacro("\$document.seqno")
    public object LastModified : LookupInMacro("\$document.last_modified")
    public object Deleted : LookupInMacro("\$document.deleted")
    public object ValueSizeBytes : LookupInMacro("\$document.value_bytes")
    public object RevId : LookupInMacro("\$document.revid")
    public object Flags : LookupInMacro("\$document.flags")

    override fun toString(): String {
        return "LookupInMacro(value='$value')"
    }
}
