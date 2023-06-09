package petpet.types.immutable;

import petpet.lang.run.PetPetCallable;
import petpet.lang.run.PetPetClass;
import petpet.lang.run.PetPetException;
import petpet.types.PetPetList;
import petpet.types.PetPetTable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class PetPetListView<T> extends PetPetList<T> {

    @Override
    public String toString() {
        return "<ListView(size=" + size() + ")>";
    }

    //don't actually extend table, even though we do java side
    public static final PetPetClass LIST_VIEW_CLASS = new PetPetClass("ListView");
    private final List<T> backingList;

    public PetPetListView(List<T> backingList) {
        super(0);
        this.backingList = backingList;
    }

    static {
        allowMethod("__get_num");
        allowMethod("len");
        allowMethod("empty");
        allowMethod("has");

        allowMethod("each");
        allowMethod("eachI");
        allowMethod("foldL");
        allowMethod("foldR");
    }

    private static void allowMethod(String name) {
        LIST_VIEW_CLASS.addMethod(name, (PetPetCallable) PetPetList.LIST_CLASS.getMethod(name));
    }

    @Override
    public int size() {
        return backingList.size();
    }

    //Disallowed methods for list view
    @Override
    public T set(int index, T element) {
        throw new PetPetException("nice try, can't set in list view");
    }
    @Override
    public void clear() {
        throw new PetPetException("nice try, can't clear list view");
    }
    @Override
    public PetPetList<T> map(PetPetCallable func) {
        throw new PetPetException("nice try, can't map list view");
    }
    @Override
    public PetPetList<T> filter(PetPetCallable func) {
        throw new PetPetException("nice try, can't filter list view");
    }
    @Override
    public T pop() {
        throw new PetPetException("nice try, can't pop list view");
    }
    @Override
    public T take() {
        throw new PetPetException("nice try, can't take from list view");
    }
    @Override
    public PetPetList<T> swap(int index1, int index2) {
        throw new PetPetException("nice try, can't swap in list view");
    }
    @Override
    public PetPetList<T> insert(int index, T value) {
        throw new PetPetException("nice try, can't insert in list view");
    }
    @Override
    public boolean contains(Object o) {
        return backingList.contains(o);
    }
    @Override
    public PetPetList<T> push(T value) {
        throw new PetPetException("nice try, can't push to list view");
    }
    @Override
    public T remove(int index) {
        throw new PetPetException("nice try, can't remove from list view");
    }
    @Override
    public PetPetListView<T> view() {
        throw new PetPetException("You found an easter egg! trying to view a view");
    }

    @Override
    public T get(int index) {
        if (!(backingList instanceof PetPetList<?>)) {
            if (index < 0) index += size();
            if (index < 0 || index >= size()) return null;
        }
        return backingList.get(index);
    }

    @Override
    public boolean isEmpty() {
        return backingList.isEmpty();
    }

    @Override
    public Iterator<T> iterator() {
        return backingList.iterator();
    }

    @Override
    public Object[] toArray() { //string format
        return backingList.toArray();
    }
}
