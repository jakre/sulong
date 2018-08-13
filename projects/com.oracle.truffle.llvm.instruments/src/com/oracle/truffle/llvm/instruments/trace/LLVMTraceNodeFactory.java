/*
 * Copyright (c) 2018, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.instruments.trace;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.ExecutionEventNodeFactory;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

final class LLVMTraceNodeFactory implements ExecutionEventNodeFactory {

    private static PrintStream createTargetStream(TruffleInstrument.Env env, String target) {
        if (target == null) {
            throw new IllegalArgumentException("Target for trace unspecified!");
        }

        final OutputStream targetStream;
        switch (target) {
            case "out":
            case "stdout":
                targetStream = env.out();
                break;

            case "err":
            case "stderr":
                targetStream = env.err();
                break;

            default:
                throw new IllegalArgumentException("Invalid target for tracing: " + target);
        }

        return new PrintStream(targetStream);
    }

    private final TruffleInstrument.Env env;
    private final TraceContext traceContext;

    LLVMTraceNodeFactory(TruffleInstrument.Env env) {
        this.env = env;
        this.traceContext = new TraceContext();
    }

    @Override
    public ExecutionEventNode create(EventContext eventContext) {
        final PrintStream target = createTargetStream(env, env.getOptions().get(LLVMTracerInstrument.TRACELLVM));

        if (eventContext.hasTag(StandardTags.RootTag.class)) {
            assert eventContext.getInstrumentedNode() != null;
            final RootNode rootNode = eventContext.getInstrumentedNode().getRootNode();
            assert rootNode != null;
            final SourceSection sourceSection = rootNode.getSourceSection();
            return new RootTrace(traceContext, target, rootNode.getName(), toTraceLine(sourceSection, false));

        } else if (eventContext.hasTag(StandardTags.StatementTag.class)) {
            return new StatementTrace(traceContext, target, toTraceLine(eventContext.getInstrumentedSourceSection(), true));

        } else {
            throw new IllegalStateException("Unknown node for tracing: " + eventContext.getInstrumentedNode());
        }
    }

    @TruffleBoundary
    private static String toTraceLine(SourceSection sourceSection, boolean includeText) {
        final StringBuilder builder = new StringBuilder();

        builder.append(sourceSection.getSource().getName());

        if (sourceSection.getStartLine() > 0) {
            builder.append(':');
            builder.append(sourceSection.getStartLine());

            if (sourceSection.getStartColumn() > 0) {
                builder.append(':');
                builder.append(sourceSection.getStartColumn());
            }
        }

        if (includeText && sourceSection.getCharLength() > 0) {
            builder.append(" -> ");
            builder.append(sourceSection.getCharacters());
        }

        return builder.toString();
    }

    private static final class TraceContext {

        private int stackDepth = 0;

        private void enterFunction() {
            stackDepth++;
        }

        private void exitFunction() {
            stackDepth--;
        }

        private int getStackDepth() {
            return stackDepth;
        }

        static String getTracePrefix() {
            return ">>";
        }
    }

    private abstract static class TraceNode extends ExecutionEventNode {

        private final TraceContext context;
        private final PrintStream out;

        TraceNode(TraceContext context, PrintStream out) {
            this.context = context;
            this.out = out;
        }

        void trace(String message) {
            out.print("[lli] ");
            for (int i = 0; i < context.getStackDepth(); i++) {
                out.print(TraceContext.getTracePrefix());
            }
            out.print(' ');
            out.println(message);
        }

        TraceContext getTraceContext() {
            return context;
        }
    }

    private static final class StatementTrace extends TraceNode {

        private final String location;

        private StatementTrace(TraceContext context, PrintStream out, String location) {
            super(context, out);
            this.location = location;
        }

        @Override
        protected void onEnter(VirtualFrame frame) {
            trace(location);
        }
    }

    private static final class RootTrace extends TraceNode {

        private final String enterPrefix;
        private final String exitPrefix;
        private final String exceptionPrefix;

        @TruffleBoundary
        RootTrace(TraceContext context, PrintStream out, String functionName, String sourceSection) {
            super(context, out);
            this.enterPrefix = String.format("Entering function %s at %s with arguments:", functionName, sourceSection);
            this.exitPrefix = "Leaving " + functionName;
            this.exceptionPrefix = "Exceptionally leaving " + functionName;
        }

        @Override
        protected void onEnter(VirtualFrame frame) {
            getTraceContext().enterFunction();
            trace(enterPrefix + Arrays.toString(frame.getArguments()));
        }

        @Override
        protected void onReturnValue(VirtualFrame frame, Object result) {
            trace(exitPrefix);
            getTraceContext().exitFunction();
        }

        @Override
        protected void onReturnExceptional(VirtualFrame frame, Throwable exception) {
            trace(exceptionPrefix);
            getTraceContext().exitFunction();
        }
    }
}
