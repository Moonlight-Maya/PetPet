package petpet.lang.run;

public interface PetPetCallable {
    Object call(Object... args);
    Object callInvoking(Object... args);
    int paramCount();
}
