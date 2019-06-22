package com.zenuml.converter;

import org.jetbrains.annotations.NotNull;

public class CallLoopTest extends BaseDslConversionTest {

    public void test_convert_to_dsl_callLoop() {
        testDslConversion("main", "CallLoop.main() {\n\tFoo foo = new Foo();\n\tBar bar = new Bar();\n\tFoo.method1(bar) {\n\t\tBar.method2(this) {\n\t\t\tFoo.method1(this)// Method re-entered\n\t\t}\n\t}\n}\n");
    }

    @NotNull
    @Override
    protected String getClassName() {
        return "callLoop.CallLoop";
    }
}