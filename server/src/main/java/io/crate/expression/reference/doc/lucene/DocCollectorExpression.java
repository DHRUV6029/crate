/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.expression.reference.doc.lucene;

import java.io.IOException;
import java.util.Map;

import io.crate.execution.engine.fetch.ReaderContext;
import io.crate.metadata.Reference;
import io.crate.metadata.doc.SysColumns;

public abstract class DocCollectorExpression<T> extends LuceneCollectorExpression<T> {

    protected final Reference ref;

    private StoredRowLookup storedRowLookup;
    private ReaderContext context;

    protected StoredRow source;

    protected DocCollectorExpression(Reference ref) {
        this.ref = ref;
    }

    @Override
    public final void startCollect(CollectorContext context) {
        storedRowLookup = context.storedRowLookup(ref);
    }

    @Override
    public final void setNextDocId(int doc) {
        this.source = storedRowLookup.getStoredRow(context, doc);
    }

    @Override
    public final void setNextReader(ReaderContext context) throws IOException {
        this.context = context;
    }

    public static LuceneCollectorExpression<?> create(final Reference reference) {
        assert reference.column().name().equals(SysColumns.DOC.name()) :
            "column name must be " + SysColumns.DOC.name();
        if (reference.column().isRoot()) {
            return new RootDocCollectorExpression(reference);
        }
        return new ChildDocCollectorExpression(reference);
    }

    static final class RootDocCollectorExpression extends DocCollectorExpression<Map<String, Object>> {

        private RootDocCollectorExpression(Reference ref) {
            super(ref);
        }

        @Override
        public Map<String, Object> value() {
            return source.asMap();
        }
    }

    static final class ChildDocCollectorExpression extends DocCollectorExpression<Object> {

        private ChildDocCollectorExpression(Reference ref) {
            super(ref);
        }

        @Override
        public Object value() {
            // correct type detection is ensured by the source parser
            return source.get(ref.column().path());
        }
    }
}
