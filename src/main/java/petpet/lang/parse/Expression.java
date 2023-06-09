package petpet.lang.parse;

import petpet.lang.lex.Lexer;
import petpet.lang.run.PetPetFunction;
import petpet.lang.compile.Bytecode;
import petpet.lang.compile.Compiler;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static petpet.lang.lex.Lexer.TokenType.*;

public abstract class Expression {

    public final int startLine;

    protected Expression(int startLine) {
        this.startLine = startLine;
    }

    public void compile(Compiler compiler) throws Compiler.CompilationException {
        compiler.acceptLineNumber(startLine);
    }

    //Scans for local declarations or upvalues and emits bytecode to push null if it finds any, and register in compiler
    public abstract void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException;

    public static class BlockExpression extends Expression {
        public final List<Expression> exprs;
        public BlockExpression(int startLine, List<Expression> exprs) {
            super(startLine);
            this.exprs = exprs;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            if (exprs.size() == 0) {
                compiler.bytecode(Bytecode.PUSH_NULL);
                return;
            }
            compiler.beginScope();
            for (int i = 0; i < exprs.size(); i++) {
                exprs.get(i).scanForDeclarations(compiler);
                exprs.get(i).compile(compiler);
                if (i != exprs.size()-1)
                    compiler.bytecode(Bytecode.POP);
            }
            compiler.endScope();
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {}
    }

    //No idea if this will work...
    public static class Return extends Expression {
        public final Expression retVal;
        protected Return(int startLine, Expression retVal) {
            super(startLine);
            this.retVal = retVal;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            retVal.compile(compiler);
            compiler.bytecode(Bytecode.RETURN);
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            retVal.scanForDeclarations(compiler);
        }
    }
    public static class IfExpression extends Expression {
        public final Expression condition, ifTrue, ifFalse;
        public IfExpression(int startLine, Expression condition, Expression ifTrue, Expression ifFalse) {
            super(startLine);
            this.condition = condition;
            this.ifTrue = ifTrue;
            this.ifFalse = ifFalse;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            condition.compile(compiler);

            int jumpElse = compiler.emitJump(Bytecode.JUMP_IF_FALSE);
            int jumpOut = -1;
            compiler.bytecode(Bytecode.POP);
            ifTrue.compile(compiler);

//            if (ifFalse != null) //if we have an else statement, emit an unconditional jump to skip it
                jumpOut = compiler.emitJump(Bytecode.JUMP);

            //Always patch the jumpElse
            compiler.patchJump(jumpElse);
            compiler.bytecode(Bytecode.POP);

            if (ifFalse != null) {
                ifFalse.compile(compiler);
//                compiler.patchJump(jumpOut);
            } else {
                compiler.bytecode(Bytecode.PUSH_NULL);
            }
            compiler.patchJump(jumpOut);
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            condition.scanForDeclarations(compiler);
            ifTrue.scanForDeclarations(compiler);
            if (ifFalse != null)
                ifFalse.scanForDeclarations(compiler);
        }
    }

    public static class While extends Expression {
        public final Expression condition, body;
        public While(int startLine, Expression condition, Expression body) {
            super(startLine);
            this.condition = condition;
            this.body = body;
        }

        /*
        ...
        push null
        START
        push condition
        if false jump to END
        > pop condition
        > pop prev result
        body
        jump to START
        END
        > pop condition
        ...
         */
        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            compiler.bytecode(Bytecode.PUSH_NULL);
            int start = compiler.startLoop(); //returns the index of the first bytecode of condition
            condition.compile(compiler);
            int endJump = compiler.emitJump(Bytecode.JUMP_IF_FALSE);
            compiler.bytecode(Bytecode.POP);
            compiler.bytecode(Bytecode.POP);
            body.compile(compiler);
            compiler.endLoop(start);
            compiler.patchJump(endJump);
            compiler.bytecode(Bytecode.POP);
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            condition.scanForDeclarations(compiler);
            body.scanForDeclarations(compiler);
        }
    }

    public static class Literal extends Expression {
        public final Object value;
        public Literal(int startLine, Object value) {
            super(startLine);
            this.value = value;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            int loc = compiler.registerConstant(value);
            if (loc < 250) {
                compiler.bytecodeWithByteArg(Bytecode.CONSTANT, (byte) loc);
            } else {
                compiler.bytecodeWithShortArg(Bytecode.BIG_CONSTANT, (short) loc);
            }
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {}
    }

    public static class Null extends Expression {
        protected Null(int startLine) {
            super(startLine);
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            compiler.bytecode(Bytecode.PUSH_NULL);
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {}
    }

    public static class ListConstructor extends Expression {
        public final List<Expression> elems;
        public ListConstructor(int startLine, List<Expression> elems) {
            super(startLine);
            this.elems = elems;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            compiler.bytecode(Bytecode.NEW_LIST);
            for (Expression elem : elems) {
                elem.compile(compiler);
                compiler.bytecode(Bytecode.LIST_ADD);
            }
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            for (Expression elem : elems)
                elem.scanForDeclarations(compiler);
        }
    }

    public static class TableConstructor extends Expression {
        public final List<Expression> keysValues;
        public TableConstructor(int startLine, List<Expression> keysValues) {
            super(startLine);
            this.keysValues = keysValues;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            compiler.bytecode(Bytecode.NEW_TABLE);
            for (int i = 0; i < keysValues.size(); i += 2) {
                //Push value then key, as it allows `map.put(pop(), pop())` in the interpreter
                keysValues.get(i+1).compile(compiler);
                keysValues.get(i).compile(compiler);
                compiler.bytecode(Bytecode.TABLE_SET);
            }
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            for (Expression expr : keysValues)
                expr.scanForDeclarations(compiler);
        }
    }

    public static class Function extends Expression {
        public final List<String> paramNames;
        public final Expression body;
        public final String funcName;

        public Function(int startLine, String funcName, List<String> argNames, Expression body) {
            super(startLine);
            this.funcName = funcName;
            this.paramNames = argNames; this.body = body;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            Compiler thisCompiler = new Compiler(compiler, null);
            for (String param : paramNames)
                thisCompiler.registerLocal(param);
            body.compile(thisCompiler);
            //Get the name
            String name;
            if (funcName == null) {
                name = "fn <anon> at " + thisCompiler.fileName + ":" + startLine;
            } else {
                name = "fn " + funcName + " at " + thisCompiler.fileName + ":" + startLine;
            }
            PetPetFunction f = thisCompiler.finish(name, startLine-1, paramNames.size());

            int idx = compiler.registerConstant(f);
            compiler.bytecodeWithByteArg(Bytecode.CONSTANT, (byte) idx);
            compiler.emitClosure(thisCompiler); //emit closure instruction
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {}
    }

    public static class Name extends Expression {
        public final String name;
        public Name(int startLine, String name) {
            super(startLine);
            this.name = name;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            int localIndex = compiler.indexOfLocal(name);
            if (localIndex != -1) {
                //If there's a local variable of this name in scope, then get local
                if (localIndex < 250)
                    compiler.bytecodeWithByteArg(Bytecode.LOAD_LOCAL, (byte) localIndex);
                else
                    compiler.bytecodeWithShortArg(Bytecode.BIG_LOAD_LOCAL, (short) localIndex);
            } else {
                int upvalueIndex = compiler.indexOfUpvalue(name);
                if (upvalueIndex != -1) {
                    //Upvalue, get it
                    if (upvalueIndex < 250)
                        compiler.bytecodeWithByteArg(Bytecode.LOAD_UPVALUE, (byte) upvalueIndex);
                    else
                        compiler.bytecodeWithShortArg(Bytecode.BIG_LOAD_UPVALUE, (short) upvalueIndex);
                } else {
                    //If neither local nor upvalue, get global
                    int loc = compiler.registerConstant(name);
                    if (loc < 250)
                        compiler.bytecodeWithByteArg(Bytecode.LOAD_GLOBAL, (byte) loc);
                    else
                        compiler.bytecodeWithShortArg(Bytecode.BIG_LOAD_GLOBAL, (short) loc);
                }
            }
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {}
    }

    public static class This extends Name {
        public This(int startLine) {
            super(startLine, "this");
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            compiler.acceptLineNumber(startLine);
            compiler.bytecodeWithByteArg(Bytecode.LOAD_LOCAL, (byte) 0);
        }
    }

    public static class Get extends Expression {
        public final Expression left;
        public final Expression indexer;
        public Get(int startLine, Expression left, Expression indexer) {
            super(startLine);
            this.left = left; this.indexer = indexer;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            left.compile(compiler);
            indexer.compile(compiler);
            compiler.bytecode(Bytecode.GET);
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            left.scanForDeclarations(compiler);
            indexer.scanForDeclarations(compiler);
        }

        public static class Strong extends Get {
            public Strong(int startLine, Expression left, Expression indexer) {
                super(startLine, left, indexer);
            }
        }
    }

    public static class Set extends Expression {
        public final Expression left;
        public final Expression index;
        public final Expression right;
        public Set(int startLine, Expression left, Expression index, Expression right) {
            super(startLine);
            this.left = left; this.index = index; this.right = right;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            left.compile(compiler);
            index.compile(compiler);
            right.compile(compiler);
            compiler.bytecode(Bytecode.SET);
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            left.scanForDeclarations(compiler);
            index.scanForDeclarations(compiler);
            right.scanForDeclarations(compiler);
        }
    }

    public static class Call extends Expression {
        public final Expression callingObject;
        public final List<Expression> args;
        public Call(int startLine, Expression left, List<Expression> args) {
            super(startLine);
            this.callingObject = left;
            this.args = args;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            callingObject.compile(compiler);
            for (Expression arg : args)
                arg.compile(compiler);
            compiler.bytecodeWithByteArg(Bytecode.CALL, (byte) args.size());
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException{
            callingObject.scanForDeclarations(compiler);
            for (Expression e : args)
                e.scanForDeclarations(compiler);
        }
    }

    public static class Invoke extends Expression {
        public final Expression instance;
        public final Expression indexer;
        public final List<Expression> args;
        public Invoke(int startLine, Expression left, Expression indexer, List<Expression> args) {
            super(startLine);
            this.instance = left;
            this.indexer = indexer;
            this.args = args;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            instance.compile(compiler);
            indexer.compile(compiler);
            for (Expression arg : args)
                arg.compile(compiler);
            compiler.bytecodeWithByteArg(Bytecode.INVOKE, (byte) args.size());
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException{
            instance.scanForDeclarations(compiler);
            indexer.scanForDeclarations(compiler);
            for (Expression e : args)
                e.scanForDeclarations(compiler);
        }
    }

    public static class Assign extends Expression {
        public final String varName;
        public final Expression rhs;
        public boolean isGlobal;

        public Assign(int startLine, boolean global, String varName, Expression rhs) {
            super(startLine);
            this.isGlobal = global;
            this.varName = varName;
            this.rhs = rhs;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            if (isGlobal) {
                int loc = compiler.registerConstant(varName);
                rhs.compile(compiler);
                if (loc < 250)
                    compiler.bytecodeWithByteArg(Bytecode.SET_GLOBAL, (byte) loc);
                else {
                    compiler.bytecodeWithShortArg(Bytecode.BIG_SET_GLOBAL, (short) loc);
                }

            } else {
                int loc = compiler.indexOfLocal(varName);
                if (loc == -1) {
                    //this is an upvalue, not a local
                    //if it was meant to be local, then
                    //indexOfLocal would not return -1, as it
                    //would have been registered during scanForDeclarations().
                    int upValueLoc = compiler.indexOfUpvalue(varName);
                    if (upValueLoc == -1) throw new Compiler.CompilationException("indexOfUpvalue shouldn't return -1, bug in compiler!", startLine);
                    rhs.compile(compiler);
                    if (upValueLoc < 250)
                        compiler.bytecodeWithByteArg(Bytecode.SET_UPVALUE, (byte) upValueLoc);
                    else
                        compiler.bytecodeWithShortArg(Bytecode.BIG_SET_UPVALUE, (short) upValueLoc);
                } else {
                    rhs.compile(compiler);
                    if (loc < 250)
                        compiler.bytecodeWithByteArg(Bytecode.SET_LOCAL, (byte) loc);
                    else
                        compiler.bytecodeWithShortArg(Bytecode.BIG_SET_LOCAL, (short) loc);
                }
            }
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            //If global, there will never be a declaration. Just search right side
            if (!isGlobal && compiler.indexOfLocal(varName) == -1) {
                //No local, look for an upvalue:
                int upvalueIndex = compiler.indexOfUpvalue(varName);
                if (upvalueIndex == -1) {
                    //upvalue doesn't exist either, so emits a
                    //"declaration". the variable doesn't exist anywhere yet
                    compiler.registerLocal(varName);
                    compiler.bytecode(Bytecode.PUSH_NULL); //reserve space for the new local on stack
                }
            }
            //Scan right side next
            rhs.scanForDeclarations(compiler);
        }
    }

    public static class Logical extends Expression {
        public final Expression left, right;
        public final boolean isAnd; //otherwise, is or
        public Logical(int startLine, boolean isAnd, Expression left, Expression right) {
            super(startLine);
            this.isAnd = isAnd;
            this.left = left;
            this.right = right;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            left.compile(compiler);
            byte code = isAnd ? Bytecode.JUMP_IF_FALSE : Bytecode.JUMP_IF_TRUE;
            int shortCircuit = compiler.emitJump(code);
            compiler.bytecode(Bytecode.POP);
            right.compile(compiler);
            compiler.patchJump(shortCircuit);
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            left.scanForDeclarations(compiler);
            right.scanForDeclarations(compiler);
        }
    }

    public static class Binary extends Expression {
        public final Expression left, right;
        public final Op op;

        public Binary(int startLine, Expression left, Op op, Expression right) {
            super(startLine);
            this.left = left; this.right = right; this.op = op;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            left.compile(compiler);
            right.compile(compiler);
            compiler.bytecode(op.bytecode);
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            left.scanForDeclarations(compiler);
            right.scanForDeclarations(compiler);
        }

        public enum Op {
            ADD(PLUS, Bytecode.ADD),
            SUB(MINUS, Bytecode.SUB),

            MUL(TIMES, Bytecode.MUL),
            DIV(DIVIDE, Bytecode.DIV),
            MOD(MODULO, Bytecode.MOD),

            EQ(EQUALS, Bytecode.EQ),
            NEQ(NOT_EQUALS, Bytecode.NEQ),
            GT(GREATER, Bytecode.GT),
            GTE(GREATER_EQUAL, Bytecode.GTE),
            LT(LESS, Bytecode.LT),
            LTE(LESS_EQUAL, Bytecode.LTE);

            private final Lexer.TokenType t;
            private final byte bytecode;
            Op(Lexer.TokenType t, byte bytecode) {
                this.t = t;
                this.bytecode = bytecode;
            }

            private static final Map<Lexer.TokenType, Op> opMap = new EnumMap(Lexer.TokenType.class) {{
                for (Op op : Op.values())
                    put(op.t, op);
            }};

            public static Op get(Lexer.TokenType type) {
                return opMap.get(type);
            }
        }

    }

    public static class Unary extends Expression {
        public final Expression expr;
        public final Op op;

        public Unary(int startLine, Op op, Expression expr) {
            super(startLine);
            this.expr = expr; this.op = op;
        }

        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            super.compile(compiler);
            expr.compile(compiler);
            compiler.bytecode(op.bytecode);
        }

        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            expr.scanForDeclarations(compiler);
        }

        public enum Op {
            NOT(Lexer.TokenType.NOT, Bytecode.NOT),
            NEGATE(Lexer.TokenType.MINUS, Bytecode.NEGATE);

            private final Lexer.TokenType t;
            private final byte bytecode;
            Op(Lexer.TokenType t, byte bytecode) {
                this.t = t;
                this.bytecode = bytecode;
            }

            private static final Map<Lexer.TokenType, Unary.Op> opMap = new EnumMap(Lexer.TokenType.class) {{
                for (Op op : Op.values())
                    put(op.t, op);
            }};

            public static Op get(Lexer.TokenType type) {
                return opMap.get(type);
            }
        }

    }

    public static class ParenExpression extends Expression {
        private final Expression inside;
        protected ParenExpression(int startLine, Expression inside) {
            super(startLine);
            this.inside = inside;
        }
        @Override
        public void compile(Compiler compiler) throws Compiler.CompilationException {
            inside.compile(compiler);
        }
        @Override
        public void scanForDeclarations(Compiler compiler) throws Compiler.CompilationException {
            inside.scanForDeclarations(compiler);
        }
    }



}
