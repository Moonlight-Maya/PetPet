package petpet.lang.run;

import petpet.lang.compile.Chunk;

/**
 * Represents a function that's written in the language itself,
 * !not! a function that's a proxy for an external java call.
 */
public class PetPetFunction {

    public final String name;
    public final Chunk chunk;
    public final int paramCount;
    public final int numUpvalues;

    public final int lineNumberOffset;
    public final int[] lineNumberTable;

    public PetPetFunction(String name, Chunk chunk, int lineNumberOffset, int[] lineNumberTable, int paramCount, int numUpvalues) {
        this.name = name;
        this.chunk = chunk;
        this.paramCount = paramCount;
        this.numUpvalues = numUpvalues;
        this.lineNumberTable = lineNumberTable;
        this.lineNumberOffset = lineNumberOffset;
    }


    public String toString() {
        return name;
    }

    public String prettyBytecode() {
        return chunk.toString(0);
    }
}
