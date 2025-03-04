package com.strumenta.kolasu.javalib;

import com.strumenta.kolasu.model.Internal;
import com.strumenta.kolasu.model.Multiplicity;
import com.strumenta.kolasu.model.Node;
import com.strumenta.kolasu.model.PropertyDescription;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class CompilationUnit extends Node {

    public static class A extends Node {
        private List<B> bs = new LinkedList<>();

        public List<B> getBs() {
            return bs;
        }

        @NotNull
        @Override
        @Internal
        public List<PropertyDescription> getProperties() {
            return Arrays.asList(new PropertyDescription("bs", true, Multiplicity.MANY, getBs()));
        }
    }

    public static class B extends Node {

    }

    private List<A> as = new LinkedList<>();

    public List<A> getAs() {
        return as;
    }

    @NotNull
    @Override
    @Internal
    public List<PropertyDescription> getProperties() {
        return Arrays.asList(new PropertyDescription("as", true, Multiplicity.MANY, getAs()));
    }
}
