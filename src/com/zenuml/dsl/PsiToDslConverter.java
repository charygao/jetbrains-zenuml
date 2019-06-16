package com.zenuml.dsl;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import io.reactivex.Observable;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static java.lang.String.format;

public class PsiToDslConverter extends JavaRecursiveElementVisitor {
    private static final Logger LOG = Logger.getInstance(PsiToDslConverter.class);

    private final MethodStack methodStack = new MethodStack();
    private final ZenDsl zenDsl = new ZenDsl();
    private final List<Class<? extends PsiExpression>> allowedConditionExpressions = Arrays.asList(PsiLiteralExpression.class,
            PsiBinaryExpression.class,
            PsiReferenceExpression.class);

    // TODO: we are not following the implementation of constructor. The behaviour is NOT defined.
    public void visitNewExpression(PsiNewExpression expression) {
        LOG.debug("Enter: visitNewExpression: " + expression);
        zenDsl.append(expression.getText()).closeExpressionAndNewLine();
        super.visitNewExpression(expression);
        LOG.debug("Exit: visitNewExpression: " + expression);
    }

    @Override
    public void visitMethod(PsiMethod method) {
        visitMethod(method, null);
    }

    private void visitMethod(PsiMethod method, String insertBefore) {
        LOG.debug("Enter: visitMethod: " + method);

        if (methodStack.contains(method)) {
            LOG.debug("Exit (loop detected): visitMethod: " + method);
            return;
        }

        String methodCall = getMethodCall(method);

        process(method, insertBefore, methodCall);

        LOG.debug("Exit: visitMethod: " + method);
    }

    private void process(PsiMethod method, String insertBefore, String methodCall) {
        if (insertBefore == null) {
            zenDsl.addMethodCall(methodCall);
            processChildren(method);
        } else {
            int index = zenDsl.getDsl().lastIndexOf(insertBefore);
            String remainder = zenDsl.getDsl().substring(index);
            zenDsl.keepHead(index);
            zenDsl.addMethodCall(methodCall);
            processChildren(method);
            zenDsl.addRemainder(remainder);
        }
    }

    private void processChildren(PsiMethod method) {
        methodStack.push(method);
        super.visitMethod(method);
        methodStack.pop();
    }

    private String getMethodCall(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        // prefix is : `ClassName.`
        String methodPrefix = methodStack
                .peekContainingClass()
                .filter(cls -> cls.equals(containingClass))
                .map(cls -> "")
                .orElse(containingClass.getName() + ".");

        return methodPrefix + method.getName();
    }

    public void visitParameterList(PsiParameterList list) {
        LOG.debug("Enter: visitParameterList: " + list);
        zenDsl.openParenthesis();
        super.visitParameterList(list);
        zenDsl.closeParenthesis();
        LOG.debug("Exit: visitParameterList: " + list);
    }

    public void visitDeclarationStatement(PsiDeclarationStatement statement) {
        LOG.debug("Enter: visitDeclarationStatement: " + statement);
        zenDsl.appendIndent();
        super.visitDeclarationStatement(statement);
    }

    public void visitExpressionStatement(PsiExpressionStatement statement) {
        LOG.debug("Enter: visitExpressionStatement: " + statement);
        zenDsl.appendIndent();
        super.visitExpressionStatement(statement);
    }

    // variable: String s = clientMethod();
    public void visitLocalVariable(PsiLocalVariable variable) {
        LOG.debug("Enter: visitLocalVariable: " + variable);
        zenDsl.appendAssignment(variable.getType().getCanonicalText(), variable.getName());
        super.visitLocalVariable(variable);
        LOG.debug("Exit: visitLocalVariable: " + variable);
    }

    @Override
    public void visitMethodCallExpression(PsiMethodCallExpression expression) {
        LOG.debug("Enter: visitMethodCallExpression: " + expression);

        super.visitMethodCallExpression(expression);

        PsiMethod method = expression.resolveMethod();
        if (method != null) {
            LOG.debug("Method resolved from expression:" + method);
            PsiElement whileChild = getDirectWhileStatementChildFromAncestors(expression);
            String insertBefore = whileChild != null && isInsideCondition(whileChild) ? "while" : null;
            visitMethod(method, insertBefore);
        }
    }

    private PsiElement getDirectWhileStatementChildFromAncestors(PsiElement element) {
        PsiElement current = element;
        PsiElement parent = current.getParent();
        while (parent != null) {
            if(parent instanceof PsiWhileStatement) return current;
            current = parent;
            parent = current.getParent();
        }
        return null;
    }

    private boolean isInsideCondition(PsiElement element) {
        PsiElement nextSibling = element.getNextSibling();
        if(nextSibling == null) return false;
        if(isRparenth(nextSibling)) return true;
        return isInsideCondition(nextSibling);
    }

    @Override
    public void visitWhileStatement(PsiWhileStatement statement) {
        LOG.debug("Enter: visitWhileStatement: " + statement);

        zenDsl.addIndent()
                .append("while")
                .openParenthesis()
                .append(getCondition(statement))
                .closeParenthesis();

        processBody(statement);
    }

    @Override
    public void visitIfStatement(PsiIfStatement statement) {
        LOG.debug("Enter: visitIfStatement: " + statement);

        zenDsl.addIndent()
                .append("if")
                .openParenthesis()
                .append(getCondition(statement))
                .closeParenthesis();

        processBody(statement);
    }

    private void processBody(PsiStatement statement) {
        boolean hasBlock = hasFollowingBraces(statement.getChildren());
        // following braces are not there, we should add them here.
        if (!hasBlock) {
            zenDsl.startBlock();
        }
        super.visitStatement(statement);
        if (!hasBlock) {
            zenDsl.closeBlock();
        }
    }

    @NotNull
    private String getCondition(PsiIfStatement statement) {
        return Arrays.stream(statement.getChildren())
                    .filter(e -> allowedConditionExpressions.stream().anyMatch(clz -> clz.isInstance(e)))
                    .findFirst().map(PsiElement::getText).orElse("condition");
    }

    @Override
    public void visitCodeBlock(PsiCodeBlock block) {
        LOG.debug("Enter: visitCodeBlock: " + block);
        if (block.getStatements().length == 0) {
            zenDsl.closeExpressionAndNewLine();
            return;
        }
        // getBody return null if the method belongs to a compiled class
        zenDsl.startBlock();
        super.visitCodeBlock(block);
        zenDsl.closeBlock();
    }

    public String getDsl() {
        return zenDsl.getDsl().toString();
    }

    private boolean hasFollowingBraces(PsiElement[] children) {
        return Arrays.stream(children).anyMatch(c -> PsiBlockStatement.class.isAssignableFrom(c.getClass()));
    }

    private String getCondition(PsiWhileStatement statement) {

        return Observable.fromArray(statement.getChildren())
                .skipWhile(psiElement -> !isLparenth(psiElement))
                .skip(1) // skip `(`
                .takeWhile(psiElement -> !isRparenth(psiElement))
                .map(PsiElement::getText)
                .reduce((s1, s2) -> s1 + s2).blockingGet();
    }

    private boolean isLparenth(PsiElement child) {
        return isParenth(child, "LPARENTH");
    }

    private boolean isRparenth(PsiElement child) {
        return isParenth(child, "RPARENTH");
    }

    private boolean isParenth(PsiElement child, String parenth) {
        return child instanceof PsiJavaToken && ((PsiJavaToken) child).getTokenType().toString().equals(parenth);
    }

}
