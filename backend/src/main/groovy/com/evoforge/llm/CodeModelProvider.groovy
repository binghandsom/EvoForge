package com.evoforge.llm

interface CodeModelProvider {
    String name()
    CodeModelClient client()
}
