/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common;

import java.io.IOException;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentLocation;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.rest.RestStatus;

/**
 * Exception that can be used when parsing queries with a given {@link
 * XContentParser}.
 * Can contain information about location of the error.
 */
public class ParsingException extends ElasticsearchException {

    protected static final int UNKNOWN_POSITION = -1;
    private final int lineNumber;
    private final int columnNumber;

    public ParsingException(XContentLocation contentLocation, String msg, Object... args) {
        super(msg, null, args);
        int lineNumber = UNKNOWN_POSITION;
        int columnNumber = UNKNOWN_POSITION;
        if (contentLocation != null) {
            lineNumber = contentLocation.lineNumber;
            columnNumber = contentLocation.columnNumber;
        }
        this.columnNumber = columnNumber;
        this.lineNumber = lineNumber;
    }


    public ParsingException(StreamInput in) throws IOException {
        super(in);
        lineNumber = in.readInt();
        columnNumber = in.readInt();
    }

    /**
     * Line number of the location of the error
     *
     * @return the line number or -1 if unknown
     */
    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * Column number of the location of the error
     *
     * @return the column number or -1 if unknown
     */
    public int getColumnNumber() {
        return columnNumber;
    }

    @Override
    public RestStatus status() {
        return RestStatus.BAD_REQUEST;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeInt(lineNumber);
        out.writeInt(columnNumber);
    }
}
