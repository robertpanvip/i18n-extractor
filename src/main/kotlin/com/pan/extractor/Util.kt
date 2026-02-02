package com.pan.extractor
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSAssignmentExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSConditionalExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReturnStatement
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.ecma6.TypeScriptFunctionExpression
import com.intellij.psi.PsiElement

object Util {
    fun isJSX(element: PsiElement): Boolean {
        // 第二步：向上遍历父节点，检查 JS 语法上下文（核心逻辑）
        var currentParent = element.parent
        while (currentParent != null) {
            // 场景1：函数返回值（return <div/>）- 所有版本都有 JSReturnStatement
            if (currentParent is JSReturnStatement) {
                return true
            }

            // 场景2：赋值表达式（a = <div/>）
            if (currentParent is JSAssignmentExpression) {
                return true
            }

            // 场景3：函数调用/参数（fn(<div/>)）- 所有版本都有 JSCallExpression
            if (currentParent is JSCallExpression) {
                return true
            }

            // 场景4：变量声明（let a = <div/>）- 通用判断（兼容所有版本）
            if (currentParent is JSVarStatement) {
                return true
            }

            // 场景5：三元表达式（flag ? <div/> : <span/>）- 所有版本都有 JSConditionalExpression
            if (currentParent is JSConditionalExpression) {
                return true
            }

            // 场景6：箭头函数（() => <div/>）- 所有版本都有 JSArrowFunctionExpression
            if (currentParent is TypeScriptFunctionExpression) {
                return true
            }

            // 场景7：对象属性（{ render: <div/> }）- 所有版本都有 JSProperty
            if (currentParent is JSProperty) {
                return true
            }

            // 场景8：数组元素（[<div/>, <span/>]）- 所有版本都有 JSArrayLiteralExpression
            if (currentParent is JSArrayLiteralExpression) {
                return true
            }

            // 场景9：export 导出（export default <div/>）
            if (currentParent is ES6ExportDefaultAssignment) {
                return true
            }
            currentParent = currentParent.parent
        }

        return false
    }
}