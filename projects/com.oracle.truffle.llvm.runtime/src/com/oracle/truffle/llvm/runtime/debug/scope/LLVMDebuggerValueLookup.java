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
package com.oracle.truffle.llvm.runtime.debug.scope;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExecutableNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.llvm.runtime.LLVMContext;
import com.oracle.truffle.llvm.runtime.LLVMLanguage;
import com.oracle.truffle.llvm.runtime.debug.LLVMDebuggerValue;
import com.oracle.truffle.llvm.runtime.debug.LLVMSourceContext;
import com.oracle.truffle.llvm.runtime.debug.value.LLVMDebugObjectBuilder;
import com.oracle.truffle.llvm.runtime.debug.value.LLVMFrameValueAccess;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMNode;

public final class LLVMDebuggerValueLookup {

    private LLVMDebuggerValueLookup() {
    }

    @TruffleBoundary
    private static LLVMDebuggerValue findValue(LLVMSourceSymbol symbol, LLVMSourceContext sourceContext, Frame frame) {
        final LLVMDebugObjectBuilder staticValue = sourceContext.getStatic(symbol);
        if (staticValue != null) {
            return staticValue.getValue(symbol);
        }

        final LLVMFrameValueAccess allocation = sourceContext.getFrameValue(symbol);
        if (allocation != null) {
            if (frame == null) {
                return LLVMDebugObjectBuilder.UNAVAILABLE.getValue(symbol);
            }
            final LLVMDebugObjectBuilder frameValue = allocation.getValue(frame);
            return frameValue.getValue(symbol);
        }

        if (frame == null) {
            return null;
        }

        for (FrameSlot slot : frame.getFrameDescriptor().getSlots()) {
            if (slot.getIdentifier() instanceof LLVMSourceSymbol && frame.getValue(slot) instanceof LLVMDebugObjectBuilder) {
                if (symbol.equals(slot.getIdentifier())) {
                    return ((LLVMDebugObjectBuilder) frame.getValue(slot)).getValue(symbol);
                }
            }
        }

        return LLVMDebugObjectBuilder.UNAVAILABLE.getValue(symbol);
    }

    @TruffleBoundary
    public static ExecutableNode findValue(LLVMLanguage language, String name, LLVMContext context, Node node) {
        final LLVMNode statementNode = LLVMDebuggerScopeFactory.findStatementNode(node);
        if (statementNode == null) {
            return null;
        }

        final LLVMSourceLocation location = statementNode.getSourceLocation();
        if (location == null) {
            return null;
        }

        for (LLVMSourceLocation scope = location; scope != null; scope = scope.getParent()) {
            for (LLVMSourceSymbol symbol : scope.getSymbols()) {
                if (name.equals(symbol.getName())) {
                    return new LLVMDynamicDebuggerValueLookup(language, symbol, context.getSourceContext());
                }
            }

            final LLVMSourceLocation compileUnit = scope.getCompileUnit();
            if (compileUnit != null) {
                for (LLVMSourceSymbol symbol : compileUnit.getSymbols()) {
                    if (name.equals(symbol.getName())) {
                        return new LLVMDynamicDebuggerValueLookup(language, symbol, context.getSourceContext());
                    }
                }
            }
        }

        return null;
    }

    private static final class LLVMDynamicDebuggerValueLookup extends ExecutableNode {

        private final LLVMSourceSymbol symbol;
        private final LLVMSourceContext context;

        private LLVMDynamicDebuggerValueLookup(LLVMLanguage language, LLVMSourceSymbol symbol, LLVMSourceContext context) {
            super(language);
            this.symbol = symbol;
            this.context = context;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return findValue(symbol, context, frame);
        }
    }
}
