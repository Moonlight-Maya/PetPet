package petpet.lang.run;

import petpet.external.PetPetWhitelist;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;

public class JavaFunction extends PetPetCallable {
    public final boolean isVoid;
    public final int paramCount;
    private final Backing backing;

    //0, 1, 2, ...
    //double, float, long, int, short, byte
    private boolean needsNumberConversion;
    private byte[] requiredTypes;

    //If non-null, is invoked with the interpreter as an argument when this function is called.
    //Currently, must read directly from the stack to find relevant args.
    //Return value is used to increment the cost.
    //This is non-final because a user may decide to add java functions reflectively, but then
    //add a cost penalizer on top of them later.
    public ToIntFunction<Interpreter> costPenalizer;

    public JavaFunction(boolean isVoid, int paramCount, ToIntFunction<Interpreter> costPenalizer) {
        this.isVoid = isVoid;
        this.paramCount = paramCount;
        this.backing = null;
        this.costPenalizer = costPenalizer;
    }

    public JavaFunction(boolean isVoid, int paramCount) {
        this(isVoid, paramCount, null);
    }

    public JavaFunction(boolean isVoid, int paramCount, boolean needsNumberConversion) {
        this(isVoid, paramCount, null);
        this.needsNumberConversion = needsNumberConversion;
    }

    private static final int MAX_PARAMS = 15;

    public JavaFunction(Method method, boolean isMethod) {
        this(method, isMethod, null);
    }

    //boolean is whether this method should be converted to a Petpet method for invocation
    //if true, it will have an implicit "this" parameter inserted
    public JavaFunction(Method method, boolean isMethod, ToIntFunction<Interpreter> costPenalizer) {
        this.costPenalizer = costPenalizer;
        if (!Modifier.isPublic(method.getModifiers()))
            throw new IllegalArgumentException(
                    "Failed to reflect method " + method.getName() + " in class " + method.getDeclaringClass() +
                            ". Sadly, we can only make java functions from public methods currently, because" +
                            "of LambdaMetafactory's restrictions."
            );
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle handle = lookup.unreflect(method);
            isVoid = method.getReturnType() == void.class;
            String methodName = isVoid ? "callVoid" : "callReturning";
            if (isMethod) {
                paramCount = method.getParameterCount() + 1;
                List<Class<?>> ptypes = new ArrayList<>(paramCount);
                for (int i = 0; i < paramCount; i++) ptypes.add(Object.class);
                MethodType samType = isVoid ? MethodType.methodType(void.class, ptypes) : MethodType.methodType(Object.class, ptypes);
                MethodType invocType = (isVoid ? handle.type().wrap().changeReturnType(void.class) : handle.type().wrap());
                MethodHandle site = LambdaMetafactory.metafactory(
                        lookup,
                        methodName, //The name of the method in the class we're implementing
                        MethodType.methodType(Backing.class), //Interface to return
                        samType, //Type of implemented method
                        handle, //A method handle for the function to wrap
                        invocType //The dynamic method type enforced at invocation time
                ).getTarget();
                backing = (Backing) site.invokeExact();
                checkNumberConversion(invocType);
            } else {
                paramCount = method.getParameterCount();
                List<Class<?>> ptypes = new ArrayList<>(paramCount);
                for (int i = 0; i < paramCount; i++) ptypes.add(Object.class);
                MethodType samType = isVoid ? MethodType.methodType(void.class, ptypes) : MethodType.methodType(Object.class, ptypes);
                MethodType invocType = isVoid ? handle.type().wrap().changeReturnType(void.class) : handle.type().wrap();
                backing = (Backing) LambdaMetafactory.metafactory(
                        lookup,
                        methodName, //The name of the method in the class we're implementing
                        MethodType.methodType(Backing.class), //Interface to return
                        samType, //Type of implemented method
                        handle, //A method handle for the function to wrap
                        invocType //The dynamic method type enforced at invocation time
                ).getTarget().invokeExact();
                checkNumberConversion(invocType);
            }
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
        if (paramCount > MAX_PARAMS)
            throw new IllegalArgumentException("Cannot create JavaFunction from method with over " + MAX_PARAMS + " params!");
    }

    @Override
    public int paramCount() {
        return paramCount;
    }

    private void checkNumberConversion(MethodType invocType) {
        Class<?>[] paramTypes = invocType.parameterArray();
        byte[] req = new byte[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> param = paramTypes[i];
            if (Number.class.isAssignableFrom(param)) {
                if (param != Double.class) {
                    needsNumberConversion = true;
                    if (param == Float.class) req[i] = 1;
                    else if (param == Long.class) req[i] = 2;
                    else if (param == Integer.class) req[i] = 3;
                    else if (param == Short.class) req[i] = 4;
                    else if (param == Byte.class) req[i] = 5;
                }
            }
        }
        requiredTypes = needsNumberConversion ? req : null;
    }

    public boolean needsNumberConversion() {
        return needsNumberConversion;
    }

    public Object castNumber(Object o, int index) {
        return switch (requiredTypes[index]) {
            case 0 -> o;
            case 1 -> ((Number) o).floatValue();
            case 2 -> ((Number) o).longValue();
            case 3 -> ((Number) o).intValue();
            case 4 -> ((Number) o).shortValue();
            case 5 -> ((Number) o).byteValue();
            default -> throw new IllegalArgumentException("Shouldn't ever happen, bug in interpreter number casting");
        };
    }

    public JavaFunction(Class<?> clazz, String name, boolean isMethod) {
        this(clazz, name, isMethod, (ToIntFunction<Interpreter>) null);
    }

    public JavaFunction(Class<?> clazz, String name, boolean isMethod, ToIntFunction<Interpreter> costPenalizer) {
        this(
                tryFindMethod(clazz, name, (Class<?>[]) null),
                isMethod,
                costPenalizer
        );
    }

    public JavaFunction(Class<?> clazz, String name, boolean isMethod, Class<?>... argTypes) {
        this(clazz, name, isMethod, null, argTypes);
    }

    public JavaFunction(Class<?> clazz, String name, boolean isMethod, ToIntFunction<Interpreter> costPenalizer, Class<?>... argTypes) {
        this(
                tryFindMethod(clazz, name, argTypes),
                isMethod,
                costPenalizer
        );
    }

    private static Method tryFindMethod(Class<?> clazz, String name, Class<?>... argTypes) {
        if (argTypes == null) {
            List<Method> methods = Arrays.stream(clazz.getMethods())
                    .filter(m -> m.getName().equals(name)).toList();
            if (methods.size() != 1)
                throw new RuntimeException("Ambiguous or incorrect JavaFunction constructor for method " + name + " in class " + clazz);
            return methods.get(0);
        }
        try {
            return clazz.getMethod(name, argTypes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object invoke() {if (isVoid) {backing.callVoid();return null;}return backing.callReturning();}public Object invoke(Object arg0) {if (isVoid) {backing.callVoid(arg0);return null;}return backing.callReturning(arg0);}public Object invoke(Object arg0, Object arg1) {if (isVoid) {backing.callVoid(arg0, arg1);return null;}return backing.callReturning(arg0, arg1);}public Object invoke(Object arg0, Object arg1, Object arg2) {if (isVoid) {backing.callVoid(arg0, arg1, arg2);return null;}return backing.callReturning(arg0, arg1, arg2);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3);return null;}return backing.callReturning(arg0, arg1, arg2, arg3);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3, arg4);return null;}return backing.callReturning(arg0, arg1, arg2, arg3, arg4);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3, arg4, arg5);return null;}return backing.callReturning(arg0, arg1, arg2, arg3, arg4, arg5);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3, arg4, arg5, arg6);return null;}return backing.callReturning(arg0, arg1, arg2, arg3, arg4, arg5, arg6);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);return null;}return backing.callReturning(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8);return null;}return backing.callReturning(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9);return null;}return backing.callReturning(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10);return null;}return backing.callReturning(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11);return null;}return backing.callReturning(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12);return null;}return backing.callReturning(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13);return null;}return backing.callReturning(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13);}public Object invoke(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14) {if (isVoid) {backing.callVoid(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14);return null;}return backing.callReturning(arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14);}

    @Override
    public Object callInvoking(Object... args) {
        return call(args); //as a java function, doesn't care about invocation or not
    }
    @Override
    public Object call(Object... args) {
        if (args.length != paramCount)
            throw new IllegalArgumentException("Expected " + paramCount + " args, got " + args.length);
        if (needsNumberConversion) {
            return switch(paramCount) {
                case 0 -> invoke();
                case 1 -> invoke(castNumber(args[0], 0));
                case 2 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1));
                case 3 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2));
                case 4 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3));
                case 5 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3), castNumber(args[4], 4));
                case 6 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3), castNumber(args[4], 4), castNumber(args[5], 5));
                case 7 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3), castNumber(args[4], 4), castNumber(args[5], 5), castNumber(args[6], 6));
                case 8 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3), castNumber(args[4], 4), castNumber(args[5], 5), castNumber(args[6], 6), castNumber(args[7], 7));
                case 9 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3), castNumber(args[4], 4), castNumber(args[5], 5), castNumber(args[6], 6), castNumber(args[7], 7), castNumber(args[8], 8));
                case 10 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3), castNumber(args[4], 4), castNumber(args[5], 5), castNumber(args[6], 6), castNumber(args[7], 7), castNumber(args[8], 8), castNumber(args[9], 9));
                case 11 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3), castNumber(args[4], 4), castNumber(args[5], 5), castNumber(args[6], 6), castNumber(args[7], 7), castNumber(args[8], 8), castNumber(args[9], 9), castNumber(args[10], 10));
                case 12 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3), castNumber(args[4], 4), castNumber(args[5], 5), castNumber(args[6], 6), castNumber(args[7], 7), castNumber(args[8], 8), castNumber(args[9], 9), castNumber(args[10], 10), castNumber(args[11], 11));
                case 13 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3), castNumber(args[4], 4), castNumber(args[5], 5), castNumber(args[6], 6), castNumber(args[7], 7), castNumber(args[8], 8), castNumber(args[9], 9), castNumber(args[10], 10), castNumber(args[11], 11), castNumber(args[12], 12));
                case 14 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3), castNumber(args[4], 4), castNumber(args[5], 5), castNumber(args[6], 6), castNumber(args[7], 7), castNumber(args[8], 8), castNumber(args[9], 9), castNumber(args[10], 10), castNumber(args[11], 11), castNumber(args[12], 12), castNumber(args[13], 13));
                case 15 -> invoke(castNumber(args[0], 0), castNumber(args[1], 1), castNumber(args[2], 2), castNumber(args[3], 3), castNumber(args[4], 4), castNumber(args[5], 5), castNumber(args[6], 6), castNumber(args[7], 7), castNumber(args[8], 8), castNumber(args[9], 9), castNumber(args[10], 10), castNumber(args[11], 11), castNumber(args[12], 12), castNumber(args[13], 13), castNumber(args[14], 14));
                default -> throw new IllegalStateException("function has too many args??");
            };
        } else {
            return switch(paramCount) {
                case 0 -> invoke();
                case 1 -> invoke(args[0]);
                case 2 -> invoke(args[0], args[1]);
                case 3 -> invoke(args[0], args[1], args[2]);
                case 4 -> invoke(args[0], args[1], args[2], args[3]);
                case 5 -> invoke(args[0], args[1], args[2], args[3], args[4]);
                case 6 -> invoke(args[0], args[1], args[2], args[3], args[4], args[5]);
                case 7 -> invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
                case 8 -> invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
                case 9 -> invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8]);
                case 10 -> invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9]);
                case 11 -> invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10]);
                case 12 -> invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11]);
                case 13 -> invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12]);
                case 14 -> invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13]);
                case 15 -> invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14]);
                default -> throw new IllegalStateException("function has too many args??");
            };
        }
    }

    public String toString() {
        return "JavaFunction";
    }

    public interface Backing {default Object callReturning(){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default Object callReturning(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}default void callVoid(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14){throw new UnsupportedOperationException("Attempt to call java function with wrong number of params??");}}

    public static void generateCode() {
        StringBuilder result = new StringBuilder("\t");
        for (int i = 0; i <= MAX_PARAMS; i++) {
            result.append("public Object invoke(");
            for (int j = 0; j < i; j++) {
                if (j != 0)
                    result.append(", ");
                result.append("Object arg").append(j);
            }
            result.append(") {if (isVoid) {backing.callVoid(");
            for (int j = 0; j < i; j++) {
                if (j != 0)
                    result.append(", ");
                result.append("arg").append(j);
            }
            result.append(");return null;}return backing.callReturning(");
            for (int j = 0; j < i; j++) {
                if (j != 0)
                    result.append(", ");
                result.append("arg").append(j);
            }
            result.append(");}");
        }
        //Backing interface
        result.append("\n\tpublic interface Backing {");
        for (int i = 0; i <= MAX_PARAMS; i++) {
            result.append("default Object callReturning(");
            for (int j = 0; j < i; j++) {
                if (j != 0)
                    result.append(", ");
                result.append("Object arg").append(j);
            }
            result.append("){throw new UnsupportedOperationException(\"Attempt to call java function with wrong number of params??\");}default void callVoid(");
            for (int j = 0; j < i; j++) {
                if (j != 0)
                    result.append(", ");
                result.append("Object arg").append(j);
            }
            result.append("){throw new UnsupportedOperationException(\"Attempt to call java function with wrong number of params??\");}");
        }
        result.append("}");
        System.out.println(result);

        /*
        result.append("\n\tpublic interface Backing {");
        for (int i = 0; i <= MAX_PARAMS; i++) {
            result.append("Object callReturning(");
            for (int j = 0; j < i; j++) {
                if (j != 0)
                    result.append(", ");
                result.append("Object arg").append(j);
            }
            result.append(");void callVoid(");
            for (int j = 0; j < i; j++) {
                if (j != 0)
                    result.append(", ");
                result.append("Object arg").append(j);
            }
            result.append(");");
        }
        result.append("}");
        System.out.println(result);

         */


        System.out.println("-----------------Switch Statement----------------");
        result = new StringBuilder("result = switch(argCount) {\n");
        for (int i = 0; i <= MAX_PARAMS; i++) {
            result.append("\t\t\t\tcase ").append(i).append(" -> jFunction.invoke(");
            for (int j = i-1; j >= 0; j--) {
                if (j != i-1)
                    result.append(", ");
                if (j == 0)
                    result.append("peek()");
                else
                    result.append("peek(").append(j).append(")");
            }
            result.append(");\n");
        }
        result.append("\t\t\tdefault -> throw new IllegalStateException(\"function has too many args??\");\n");
        result.append("\t\t\t};");
        System.out.println(result);

        System.out.println("-----------------Switch Statement With Number Casts----------------");
        result = new StringBuilder("result = switch(paramCount) {\n");
        for (int i = 0; i <= MAX_PARAMS; i++) {
            result.append("\t\t\t\tcase ").append(i).append(" -> jFunction.invoke(");
            for (int j = i-1; j >= 0; j--) {
                if (j != i-1)
                    result.append(", ");
                if (j == 0)
                    result.append("jFunction.castNumber(peek(), ").append(i-1-j).append(")");
                else
                    result.append("jFunction.castNumber(peek(").append(j).append("), ").append(i-1-j).append(")");
            }
            result.append(");\n");
        }
        result.append("\t\t\tdefault -> throw new IllegalStateException(\"function has too many args??\");\n");
        result.append("\t\t\t};");
        System.out.println(result);

        System.out.println("-----------------JavaFunction.call Impl----------------");
        result = new StringBuilder("if (needsNumberConversion) {\n\t\t\treturn switch(argCount) {\n");
        for (int i = 0; i <= MAX_PARAMS; i++) {
            result.append("\t\t\t\tcase ").append(i).append(" -> invoke(");
            for (int j = 0; j < i; j++) {
                if (j != 0)
                    result.append(", ");
                result.append("castNumber(args[").append(j).append("], ").append(j).append(")");
            }
            result.append(");\n");
        }
        result.append("\t\t\tdefault -> throw new IllegalStateException(\"function has too many args??\");\n");
        result.append("\t\t\t};\n");
        result.append("\t\t\t} else {\n\t\t\treturn switch(paramCount) {\n");
        for (int i = 0; i <= MAX_PARAMS; i++) {
            result.append("\t\t\t\tcase ").append(i).append(" -> invoke(");
            for (int j = 0; j < i; j++) {
                if (j != 0)
                    result.append(", ");
                result.append("args[").append(j).append("]");
            }
            result.append(");\n");
        }
        result.append("\t\t\tdefault -> throw new IllegalStateException(\"function has too many args??\");\n");
        result.append("\t\t\t};\n\t\t}");
        System.out.println(result);
    }
}


