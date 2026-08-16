package com.pan.extractor

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSBlockStatement
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * 从 [I18nProcessor] 拆分出的「翻译调用 import / i18n 实例注入」方法群。
 * 持有 [I18nProcessor] 引用以访问其内部状态（工厂、辅助方法、脚本标签定位等）。
 */
class I18nImportInjector(private val processor: I18nProcessor) {

    // ───────────────────────────────────────────────
    // Vue import 去重 / 解构去重 工具函数（问题 4 修复）
    // ───────────────────────────────────────────────
    /**
     * 判断 [decl]（一个 ES6ImportDeclaration）是否已经从 [moduleName] 导入了 [wantedName]。
     *
     * 改用"文本级宽松匹配"而不是 IntelliJ PSI 内部 API：
     * 不同版本的 IntelliJ（2024/2025 EAP）对 ES6ImportDeclaration 的内部属性
     * 名字变化很大（importedModule / importedNamespaceBinding / importedName 等都不存在），
     * 但 `decl.text` 即源代码字符串是稳定的。
     *
     * 匹配规则：
     * - decl.text 必须包含 `from "...moduleName..."` 或 `from '...moduleName...'`
     *   （相对路径还允许 `/index` 尾缀）
     * - 然后看整个 import 里是否包含 wantedName：
     *     1) 命名导入：`{ useI18n }` / `{ useI18n as i18n }` / `{ foo, useI18n }`
     *     2) 命名空间导入：`import * as X from` → 视为"已经处理过"
     *     3) 默认导入：默认变量名 == wantedName 或 wantedName == "default"
     */
    fun hasImportedSpecifier(decl: ES6ImportDeclaration, moduleName: String, wantedName: String): Boolean =
        I18nPsiTools.hasImportedSpecifier(decl, moduleName, wantedName)

    /**
     * 判断 [scope] 范围内是否已经存在"[callee]() 函数调用 + 指定解构"。
     * 用于避免 `const { t: \$t } = useI18n();` 在 ensureVueI18nImported 里重复插入。
     *
     * 文本级宽松匹配：
     * 1) scope 文本里出现 `useI18n(`（即调用过），
     * 2) 并且要么
     *    - const/let/var 解构文本中包含 `{ $destructureNameFrom: $destructureAlias }`
     *    - 或者 `useI18n(` 附近存在 `{$destructureNameFrom`（比如用户自己写 const { t, n } = useI18n()）
     *       就认为"已经处理过"，不重复塞。
     */
    fun scopeHasDestructuredCall(
        scope: PsiElement,
        callee: String,
        destructureNameFrom: String,
        destructureAlias: String,
    ): Boolean = I18nPsiTools.scopeHasDestructuredCall(
        scope, callee, destructureNameFrom, destructureAlias
    )

    fun ensureVueI18nImported(psiFile: PsiElement) {
        val scriptTag = processor.getScriptTag() ?: run {
            val script = processor.factory.createHTMLTagFromText("<script setup lang=\"ts\">\n\n</script>")
            psiFile.add(script);
            processor.getScriptTag()
        } ?: return;
        // 關鍵：找到 <script> 內部的文本節點（注入宿主通常在這裡）
        val scriptContent = PsiTreeUtil.findChildOfType(scriptTag, JSEmbeddedContent::class.java)
        if (scriptContent === null) {
            return
        }
        val importStatements = PsiTreeUtil.findChildrenOfType(scriptContent, ES6ImportDeclaration::class.java)

        // ── 修复问题 4（重复注入）：语义级判定 import 是否存在 ──
        val importUseI18nExists = importStatements.any {
            hasImportedSpecifier(it, moduleName = "vue-i18n", wantedName = "useI18n")
        }
        val constUseI18nExists = scopeHasDestructuredCall(
            scriptContent,
            callee = "useI18n",
            destructureNameFrom = "t",
            destructureAlias = "\$t"
        )

        // 1. 创建 import 语句
        val importUseI18nNode = processor.createStringExpressionNode("import { useI18n } from 'vue-i18n';", psiFile)
        // 2. 创建 const 语句
        val constUseI18nNode = processor.createStringExpressionNode("const { t: \$t } = useI18n();", psiFile)

        if (importStatements.isEmpty()) {
            // 没有 import，直接加到内容最前面（或合适位置）
            val addedImport = scriptContent.addAfter(importUseI18nNode, scriptContent.firstChild)
            val whiteSpace = scriptContent.addAfter(processor.createStringExpressionNode("\n", psiFile), addedImport)
            if (!constUseI18nExists) scriptContent.addAfter(constUseI18nNode, whiteSpace)
        } else {
            if (!importUseI18nExists) {
                // 有 import → 新 import 加到第一个 import 前面
                val firstImport = importStatements.first()
                firstImport.parent.addBefore(importUseI18nNode, firstImport)
            }
            if (!constUseI18nExists) {
                // const 加到最后一个 import 后面
                val lastImport = importStatements.last()
                lastImport.parent.addAfter(constUseI18nNode, lastImport)
            }
        }
    }

    /** React i18n 导入 + useTranslation hook 注入 */
    fun ensureReactI18nImported(psiFile: PsiElement) {
        val containingFile = psiFile.containingFile ?: return

        // Bug 1 修复：先确认文件中真正存在"能合法调用 useTranslation 的地方"，
        // 否则直接 return，避免给纯工具/纯常量/纯配置文件注入 useTranslation import。
        // 合法注入位置：
        //   1) React 函数组件/类组件的 render 函数体（findReactComponentFunctions）
        //   2) React 项目中 .ts/.tsx 文件里的自定义 hook（use 开头的顶级函数）
        val componentFuncs = Util.findReactComponentFunctions(containingFile)
        val hookFuncs = Util.findHookFunctions(containingFile)
        val allTargets = (componentFuncs.asSequence() + hookFuncs.asSequence())
            .distinct()
            .toList()
        if (allTargets.isEmpty()) return

        // 1. 确保 react-i18next 导入存在（仅当有合法调用目标时才注入 import）
        //    注意：必须按"是否已导入 useTranslation"去重，而不是按模块名 react-i18next——
        //    否则顶部已有 `import { getI18n } from 'react-i18next'` 时会把 useTranslation
        //    import 吞掉，导致组件里用了 useTranslation 却没导入（运行时报错）。
        val imports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
        if (imports.none { it.text.contains("useTranslation") }) {
            val importText = "import { useTranslation } from 'react-i18next';\n"
            val importStmt = processor.createJSStatementFromText(importText, containingFile)
            if (imports.isNotEmpty()) {
                val firstImport = imports.first()
                firstImport.parent.addBefore(importStmt, firstImport)
            } else {
                // 没有 import 时，加到文件最开头（第一个有效语句之前）
                val firstStatement = findFirstNonWhitespaceChild(containingFile)
                if (firstStatement != null) {
                    containingFile.addBefore(importStmt, firstStatement)
                } else {
                    containingFile.add(importStmt)
                }
            }
        }

        // 2. 逐个注入（从后往前插入，避免 offset 偏移）
        // 使用 PSI 操作创建语句并插入，全部使用纯 PSI 操作避免 Document locked 异常
        for (func in allTargets.asReversed()) {
            val body = PsiTreeUtil.findChildOfType(func, JSBlockStatement::class.java) ?: continue
            // 检查是否已存在 useTranslation 调用
            val existingVars = PsiTreeUtil.findChildrenOfType(body, JSVarStatement::class.java)
            if (existingVars.none { it.text.contains("useTranslation") }) {
                val hookStmt = processor.createJSStatementFromText(
                    "\n    const { t } = useTranslation();",
                    func
                )
                // 插入到 body 的 '{' 之后（即第一个 LeafElement 之后）
                val openingBrace = body.firstChild
                if (openingBrace != null) {
                    body.addAfter(hookStmt, openingBrace)
                }
            }
        }
    }

    /**
     * Vue 项目纯 .ts 文件中 use 开头自定义 hook 的 useI18n 注入。
     *
     * 场景：Vue 项目里独立的 .ts 文件（非 .vue SFC）写了 useXxx 自定义 hook，
     * 内部有硬编码中文。这类文件既不是 Vue SFC（无 <script> 标签）也不是 React，
     * 无法走 [ensureVueI18nImported]（依赖 <script>）或 [ensureReactI18nImported]。
     *
     * 处理：
     * 1. 缺少 vue-i18n 导入时，在文件顶部注入 `import { useI18n } from 'vue-i18n'`
     * 2. 给每个 use 开头的顶级 hook 函数体首行注入 `const { t: $t } = useI18n();`
     */
    fun ensureVueHookI18nImported(psiFile: PsiElement) {
        val containingFile = psiFile.containingFile ?: return

        // 0. 先找到所有 use 开头的 hook 函数，没有则直接返回
        //    （避免普通 TS 文件被注入 vue-i18n import）
        val hookFuncs = Util.findHookFunctions(containingFile)
        if (hookFuncs.isEmpty()) return

        // 1. 确保 vue-i18n 导入存在
        val imports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
        if (imports.none { it.text.contains("vue-i18n") }) {
            val importText = "import { useI18n } from 'vue-i18n';\n"
            val importStmt = processor.createJSStatementFromText(importText, containingFile)
            if (imports.isNotEmpty()) {
                val firstImport = imports.first()
                firstImport.parent.addBefore(importStmt, firstImport)
            } else {
                val firstStatement = findFirstNonWhitespaceChild(containingFile)
                if (firstStatement != null) {
                    containingFile.addBefore(importStmt, firstStatement)
                } else {
                    containingFile.add(importStmt)
                }
            }
        }

        // 3. 逐个注入（从后往前插入，避免 offset 偏移）
        for (func in hookFuncs.asReversed()) {
            val body = PsiTreeUtil.findChildOfType(func, JSBlockStatement::class.java) ?: continue
            // 检查是否已存在 useI18n 调用
            val existingVars = PsiTreeUtil.findChildrenOfType(body, JSVarStatement::class.java)
            if (existingVars.none { it.text.contains("useI18n") }) {
                val hookStmt = processor.createJSStatementFromText(
                    "\n    const { t: \$t } = useI18n();",
                    func
                )
                val openingBrace = body.firstChild
                if (openingBrace != null) {
                    body.addAfter(hookStmt, openingBrace)
                }
            }
        }
    }

    /**
     * 【Vue TSX 组件的 useI18n 注入】（对应 ensureVueHookI18nImported 的"组件版"）
     *
     * 场景：Vue 项目中的 .tsx / .jsx 文件写了 defineComponent({...}) 或 PascalCase
     *       函数式组件，内部有硬编码中文需要替换为 $t('key')，此时需要：
     *       1) 顶部存在 `import { useI18n } from 'vue-i18n'`；
     *       2) defineComponent 的 setup() 函数体开头，或函数式组件函数体开头，
     *          注入 `const { t: $t } = useI18n()`；
     *       这样 $t 在组件作用域可用，不需要"全局 i18n 实例 + const $t = i18n.global.t"。
     *
     * 注入目标集合（allTargets，每一个都要在函数体开头插 const 解构）：
     *   A. defineComponent({ setup() { ... } }) 中找到的 setup() 函数体
     *   B. findVueComponentFunctions 返回的函数式组件（PascalCase + return JSX）
     *   C. findVueComponentFunctions 返回的 defineComponent 调用 → 定位 setup 属性
     *
     * 注意：和 ensureVueHookI18nImported 对称——如果文件里找不到任何组件函数，直接 return。
     */
    fun ensureVueComponentI18nInjected(psiFile: PsiElement) {
        val containingFile = psiFile.containingFile ?: return
        val vueComponents = Util.findVueComponentFunctions(containingFile)
        if (vueComponents.isEmpty()) return

        // —— 阶段 1：先确认要注入 const 解构的"函数体列表"
        val targetBodies = mutableListOf<JSBlockStatement>()

        for (cand in vueComponents) {
            when {
                // 类型 1：cand 就是一个函数式组件（defineComponent 场景 2 返回的 JSFunction）
                cand is JSFunction -> {
                    val body = PsiTreeUtil.findChildOfType(cand, JSBlockStatement::class.java)
                    if (body != null) targetBodies.add(body)
                }
                // 类型 2：cand 是 defineComponent({ setup(){...} }) 的调用（JSCallExpression）
                cand is com.intellij.lang.javascript.psi.JSCallExpression -> {
                    // 在 cand 的整个子树里找 setup: 属性
                    // （defineComponent 的第一个参数就是 { setup: ... } 对象字面量）
                    // 直接用 findChildrenOfType(JSProperty) 搜索，避免 SDK 版本差异
                    // （arguments 类型在不同 SDK 版本不一致，但属性一定是 JSProperty 的后代）。
                    val setupProp = PsiTreeUtil.findChildrenOfType(cand, JSProperty::class.java)
                        .firstOrNull { it.name == "setup" } ?: continue
                    // setup 可能是三种形态，且不同 IntelliJ 版本解析结构不一致：
                    //   · 简写方法 setup() {}        → 直接在 setupProp 下展开 PARAMETER_LIST + JSBlockStatement
                    //     （JSFunction 也能 find 到，但其下的 block 可能是空 → 需要在 setupProp 直接找）
                    //   · setup: function(){}        → JSFunction + block
                    //   · setup: () => {}            → JSFunction(箭头类型，SDK 内 JSFunction 可识别) + block
                    // 兼容所有结构：**先在 setupFunc（JSFunction）内找 block，找不到就退到 setupProp 内找**
                    val setupFunc = PsiTreeUtil.findChildOfType(setupProp, JSFunction::class.java)
                    // ── 修复 Bug3：setup() 里用 useRequest(async () => { ... }) 时，
                    //    findChildOfType 深度遍历会返回 useRequest 回调内部的箭头函数字符串块，
                    //    导致解构被错误注入到 useRequest 回调体首行（而非 setup 顶层）。
                    //    正确做法：
                    //      1) 优先取 setupFunc 的「直接子节点」 JSBlockStatement（不是后代）；
                    //      2) 否则从 setupProp 的「第一层直接后代 JSBlockStatement」拿：
                    //         即 从 setupProp.subtree 中第一个 JSBlockStatement，它的 parent
                    //         要么就是 setupFunc（JSFunction），要么就是 setup 属性本身
                    //         （如对象字面量简写属性 setup(){ ... } 的 block 直接后代）。
                    fun findDirectBlockIn(ancestor: PsiElement): JSBlockStatement? {
                        for (child in ancestor.children) {
                            if (child is JSBlockStatement) return child
                            val found = findDirectBlockIn(child)
                            if (found != null) return found
                        }
                        return null
                    }
                    val body: JSBlockStatement =
                        ((if (setupFunc != null) findDirectBlockIn(setupFunc) else null)
                            ?: findDirectBlockIn(setupProp))
                            ?: continue
                    targetBodies.add(body)
                }
            }
        }
        // 没找到任何组件函数体 → 不注入 import（避免无意义的 vue-i18n 注入）
        if (targetBodies.isEmpty()) return

        // —— 阶段 2：保证 vue-i18n import 存在（复用 ensureVueHookI18nImported 的同一段逻辑）
        val imports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
        if (imports.none { it.text.contains("vue-i18n") }) {
            val importText = "import { useI18n } from 'vue-i18n';\n"
            val importStmt = processor.createJSStatementFromText(importText, containingFile)
            if (imports.isNotEmpty()) {
                imports.first().parent.addBefore(importStmt, imports.first())
            } else {
                val firstStatement = findFirstNonWhitespaceChild(containingFile)
                if (firstStatement != null) {
                    containingFile.addBefore(importStmt, firstStatement)
                } else {
                    containingFile.add(importStmt)
                }
            }
        }

        // —— 阶段 3：从后往前，在每个组件函数体首行插 `const { t: $t } = useI18n();`（去重）
        for (body in targetBodies.asReversed()) {
            val existingVars = PsiTreeUtil.findChildrenOfType(body, JSVarStatement::class.java)
            if (existingVars.any { it.text.contains("useI18n") }) continue
            val hookStmt = processor.createJSStatementFromText(
                "\n    const { t: \$t } = useI18n();",
                body
            )
            val openingBrace = body.firstChild ?: continue
            body.addAfter(hookStmt, openingBrace)
        }
    }

    /**
     * 当文件使用 i18n.global.t / i18n.t / getI18n().t 但缺少 i18n 实例导入时，注入默认导入。
     *
     * - Vue:   查找项目中调用 createI18n 的文件（通常位于 @/locales 目录），
     *          根据该文件的实际路径与导出方式生成导入：
     *            `import { i18n } from '@/locales'`   （命名导出，别名路径）
     *            `import i18n from './locales/index'` （默认导出，相对路径）
     *          找不到 createI18n 文件时回退到从 vue-i18n 包导入。
     *          （可选 injectGlobalDollarT=true：追加 const \$t = i18n.global.t;）
     *
     * - React 旧模式：`import i18n from 'i18next'`（i18next 全局实例，兼容已存在导入场景）
     *
     * - React 新模式（用户要求）：统一用 \$t 减少复杂度，顶部写两行：
     *       import { getI18n } from 'react-i18next';
     *       const $t = getI18n().t;
     *   由 injectReactGlobalDollarT=true 开启。
     *
     * 注意：已有任意形式的 i18n / getI18n 导入时不重复注入。
     */
    /**
     * 确保文件顶部已经导入了 i18n 实例。
     *
     * @param injectGlobalDollarT —— 仅在 isVue=true 且"非 SFC 的纯 TS 文件"时为 true：
     *   在已经 `import { i18n } from '@/locales/xxx'` 之后，再追加一行
     *   `const $t = i18n.global.t;`（去重，用户要求"全部统一用 $t 减少复杂度"）。
     *
     * @param injectReactGlobalDollarT —— 仅在 isVue=false 且"React 纯工具 TS（无组件无 Hook）"时为 true：
     *   顶部注入 `import { getI18n } from 'react-i18next';`，并在其后追加
     *   `const $t = getI18n().t;`（去重）。
     */
    fun ensureI18nInstanceImported(
        psiFile: PsiElement,
        isVue: Boolean,
        injectGlobalDollarT: Boolean = false,
        injectReactGlobalDollarT: Boolean = false
    ) {
        // ── 局部 helper：先声明，后面才能用 ─────────────────────────────
        // Vue 版：检查是否已经存在 `const $t = i18n.global.t`（宽松空格/分号容忍）
        fun hasVueGlobalDollarTAliased(root: PsiElement): Boolean {
            val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
            val re = Regex("""const\s*\{\s*[\s\S]*\}\s*=\s*i18n\s*\.\s*global\s*\.\s*t""")
            val reSimple = Regex("""const\s+\${'$'}t\s*=\s*i18n\s*\.\s*global\s*\.\s*t""")
            return vars.any {
                re.containsMatchIn(it.text.replace("\\s+", "")) ||
                    reSimple.containsMatchIn(it.text) ||
                    it.text.replace("\\s+", "").let { t ->
                        t.contains("const\$t=i18n.global.t")
                    }
            }
        }
        // React 版：检查是否已经存在 `const t = getI18n().t`（或兼容老写法 `const $t = getI18n().t`）
        fun hasReactGlobalDollarTAliased(root: PsiElement): Boolean {
            val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
            val reT = Regex("""const\s+t\s*=\s*getI18n\s*\(\s*\)\s*\.\s*t""")
            val reDollarT = Regex("""const\s+\${'$'}t\s*=\s*getI18n\s*\(\s*\)\s*\.\s*t""")
            val reTLocale = Regex("""const\s+t\s*=\s*i18n\s*\.\s*t""")
            return vars.any {
                reT.containsMatchIn(it.text) ||
                    reDollarT.containsMatchIn(it.text) ||
                    reTLocale.containsMatchIn(it.text) ||
                    it.text.replace("\\s+", "").let { t ->
                        t.contains("constt=getI18n().t") ||
                            t.contains("const\$t=getI18n().t") ||
                            t.contains("constt=i18n.t")
                    }
            }
        }
        // React 版：检查是否已经存在 `const i18n = getI18n()` 别名
        // （i18n.t 语义 + locale 不可用时，用该别名保持 i18n 标识符可用）
        fun hasReactI18nGlobalAliased(root: PsiElement): Boolean {
            val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
            val re = Regex("""const\s+i18n\s*=\s*getI18n\s*\(\s*\)""")
            return vars.any {
                re.containsMatchIn(it.text) ||
                    it.text.replace("\\s+", "").let { t -> t.contains("consti18n=getI18n()") }
            }
        }
        // React 版：检查是否已存在 getI18n 的 const 别名（$t 或 i18n 均可）
        // → 判定"文件已经在用 getI18n"，避免切 locale 造成别名错位。
        fun hasReactGetI18nAlias(root: PsiElement): Boolean =
            hasReactGlobalDollarTAliased(root) || hasReactI18nGlobalAliased(root)
        // ───────────────────────────────────────────────────────────────

        val i18nAlreadyImported = hasI18nInstanceImported(psiFile)

        // React：统一"locale 优先、getI18n 回退"。
        //  - reactLocaleImport：项目 locale 初始化文件导出了 i18n → 用它的导入语句；
        //  - 否则回退 `import { getI18n } from 'react-i18next'`（不再硬编码 `import i18n from 'i18next'`）；
        //  - 若文件已经用 getI18n（import 或 const 别名），保持 getI18n 不切 locale，避免别名错位。
        val alreadyUsesGetI18n = !isVue && (hasReactGetI18nImported(psiFile) || hasReactGetI18nAlias(psiFile))
        val reactLocaleImport =
            if (isVue || alreadyUsesGetI18n) null
            else buildReactI18nInstanceImport(psiFile.containingFile ?: psiFile)

        // React $t 模式（injectReactGlobalDollarT=true，纯工具 TS 无组件无 Hook）：
        //  - locale 可用：$t 指向 locale 导入的 i18n → 已有任意 i18n 实例导入即视为满足；
        //  - locale 不可用：$t = getI18n().t → **必须严格存在 getI18n 命名导入**才算满足。
        //    老 `import i18n from 'i18next'` 顶不上——否则又出现用户报告过的问题 2：
        //    只追加 const $t = getI18n().t、却没补 import { getI18n }，运行时报 getI18n is not defined。
        val reactDollarTImportSatisfied =
            if (reactLocaleImport != null) i18nAlreadyImported else hasReactGetI18nImported(psiFile)

        val requiredImportAlreadyPresent = when {
            isVue -> i18nAlreadyImported
            injectReactGlobalDollarT -> reactDollarTImportSatisfied
            // i18n.t 语义：只要已有任意指向 i18n 实例的导入（locale / ./i18n / i18next / getI18n）即满足
            else -> i18nAlreadyImported
        }

        // $t 全局别名是否已经存在：对应各自 helper 才叫存在
        val dollarTAliasAlreadyPresent = when {
            isVue && injectGlobalDollarT -> hasVueGlobalDollarTAliased(psiFile)
            !isVue && injectReactGlobalDollarT -> hasReactGlobalDollarTAliased(psiFile)
            else -> true // 不需要 $t 别名 → 当然算"已存在"
        }
        // React i18n.t 语义（injectReactGlobalDollarT=false）且 locale 不可用、又缺 import 时，
        // 回退 getI18n 需要 `const i18n = getI18n();` 保持 i18n 标识符可用。
        val reactI18nAliasAlreadyPresent = !isVue && hasReactI18nGlobalAliased(psiFile)
        val reactNeedsI18nAlias = !isVue && !injectReactGlobalDollarT &&
            reactLocaleImport == null && !requiredImportAlreadyPresent

        // 提前 return：(所需的 import 已存在) 且 (不需要追加别名 OR 别名也已经追加过)
        val stillNeedConstAlias = (isVue && injectGlobalDollarT) ||
            (!isVue && injectReactGlobalDollarT) ||
            reactNeedsI18nAlias
        val aliasAlreadyPresent = if (reactNeedsI18nAlias) reactI18nAliasAlreadyPresent else dollarTAliasAlreadyPresent
        if (requiredImportAlreadyPresent && !(stillNeedConstAlias && !aliasAlreadyPresent)) {
            return
        }

        // —— 计算 import 文本 & const 别名文本 ——
        val importText: String? = when {
            requiredImportAlreadyPresent -> null
            isVue -> buildVueI18nInstanceImport(psiFile.containingFile ?: psiFile)
            reactLocaleImport != null -> reactLocaleImport
            else -> "import { getI18n } from 'react-i18next';\n"
        }
        val dollarTText: String? = when {
            isVue && injectGlobalDollarT && !dollarTAliasAlreadyPresent -> "const \$t = i18n.global.t;\n"
            // React t 别名：locale → i18n.t；回退 → getI18n().t
            !isVue && injectReactGlobalDollarT && !dollarTAliasAlreadyPresent ->
                if (reactLocaleImport != null) "const t = i18n.t;\n" else "const t = getI18n().t;\n"
            // React i18n.t 语义 + 回退 getI18n：注入 const i18n = getI18n() 保持 i18n 标识符可用
            reactNeedsI18nAlias && !reactI18nAliasAlreadyPresent -> "const i18n = getI18n();\n"
            else -> null
        }

        if (isVue) {
            // 分两种情况：
            // A) .vue SFC：需要注入到文件内部的 <script> 内（getScriptTag() 能找到）
            // B) 纯 TS/JS 文件（用户新场景：needInjectGlobalDollarT=true 的典型宿主）：
            //    就跟 React 一样直接写到 PsiFile 顶部
            val sfcScript = processor.getScriptTag()
            if (sfcScript != null) {
                val scriptContent = PsiTreeUtil.findChildOfType(sfcScript, JSEmbeddedContent::class.java)
                    ?: return
                val importStatements = PsiTreeUtil.findChildrenOfType(scriptContent, ES6ImportDeclaration::class.java)
                val dollarTAlreadyAliased = hasVueGlobalDollarTAliased(scriptContent)

                // 1) Import 注入（如果需要）
                if (importText != null) {
                    val importStmt = processor.createStringExpressionNode(importText, scriptContent)
                    if (importStatements.isNotEmpty()) {
                        val firstImport = importStatements.first()
                        firstImport.parent.addBefore(importStmt, firstImport)
                    } else {
                        val firstStatement = findFirstNonWhitespaceChild(scriptContent)
                        if (firstStatement != null) {
                            scriptContent.addBefore(importStmt, firstStatement)
                        } else {
                            scriptContent.add(importStmt)
                        }
                    }
                }
                // 2) $t 全局别名注入（如果需要且没有）
                if (injectGlobalDollarT && dollarTText != null && !dollarTAlreadyAliased) {
                    val stmt = processor.createStringExpressionNode(dollarTText, scriptContent)
                    val lastImport = importStatements.lastOrNull()
                    if (lastImport != null) {
                        lastImport.parent.addAfter(stmt, lastImport)
                    } else {
                        val firstStatement = findFirstNonWhitespaceChild(scriptContent)
                        if (firstStatement != null) {
                            scriptContent.addBefore(stmt, firstStatement)
                        } else {
                            scriptContent.add(stmt)
                        }
                    }
                }
            } else {
                // —— Case B: 纯脚本文件，直接写到 PsiFile 顶部（React 相同的位置注入逻辑，
                //            但 $t 别名用字符串 LeafPSI，避免 createJSStatementFromText
                //            在缺少 JS language 上下文时失败）
                val containingFile = psiFile.containingFile ?: return
                val imports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
                val dollarTAlreadyAliased = hasVueGlobalDollarTAliased(containingFile)

                // 1) Import 注入（如果需要）
                if (importText != null) {
                    val importStmt = processor.createJSStatementFromText(importText, containingFile)
                    if (imports.isNotEmpty()) {
                        val firstImport = imports.first()
                        firstImport.parent.addBefore(importStmt, firstImport)
                    } else {
                        val firstStatement = findFirstNonWhitespaceChild(containingFile)
                        if (firstStatement != null) {
                            containingFile.addBefore(importStmt, firstStatement)
                        } else {
                            containingFile.add(importStmt)
                        }
                    }
                }
                // 2) $t 全局别名：位置 = import 语句（含刚注入的）之后的首行；若仍无 import
                //    就放在文件首部。为了去重，先在 ES6ImportDeclaration 的 PSI 树上操作。
                if (injectGlobalDollarT && dollarTText != null && !dollarTAlreadyAliased) {
                    val stmt = processor.createStringExpressionNode(dollarTText, containingFile)
                    val latestImports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
                    if (latestImports.isNotEmpty()) {
                        val lastImport = latestImports.last()
                        lastImport.parent.addAfter(stmt, lastImport)
                    } else {
                        val firstStatement = findFirstNonWhitespaceChild(containingFile)
                        if (firstStatement != null) {
                            containingFile.addBefore(stmt, firstStatement)
                        } else {
                            containingFile.add(stmt)
                        }
                    }
                }
            }
        } else {
            // —— React: 注入到文件顶部
            // 旧模式：只有 import i18n from 'i18next'（dollarTText=null，因为 tFunctionName 是 i18n.t）
            // 新模式：import { getI18n } from 'react-i18next' + const $t = getI18n().t;
            val containingFile = psiFile.containingFile ?: return
            val imports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
            val dollarTAlreadyAliased = hasReactGlobalDollarTAliased(containingFile)

            // 1) Import 注入（如果需要）
            if (importText != null) {
                val importStmt = processor.createJSStatementFromText(importText, containingFile)
                if (imports.isNotEmpty()) {
                    val firstImport = imports.first()
                    firstImport.parent.addBefore(importStmt, firstImport)
                } else {
                    val firstStatement = findFirstNonWhitespaceChild(containingFile)
                    if (firstStatement != null) {
                        containingFile.addBefore(importStmt, firstStatement)
                    } else {
                        containingFile.add(importStmt)
                    }
                }
            }
            // 2) React 别名注入：$t 别名（injectReactGlobalDollarT）或 i18n 别名
            //    （reactNeedsI18nAlias：i18n.t 语义 + locale 不可用 + 无导入 → const i18n = getI18n()）
            val needReactConstAlias =
                (injectReactGlobalDollarT && dollarTText != null && !dollarTAlreadyAliased) ||
                    (reactNeedsI18nAlias && dollarTText != null && !reactI18nAliasAlreadyPresent)
            if (needReactConstAlias) {
                val stmt = processor.createStringExpressionNode(dollarTText, containingFile)
                val latestImports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
                if (latestImports.isNotEmpty()) {
                    val lastImport = latestImports.last()
                    lastImport.parent.addAfter(stmt, lastImport)
                } else {
                    val firstStatement = findFirstNonWhitespaceChild(containingFile)
                    if (firstStatement != null) {
                        containingFile.addBefore(stmt, firstStatement)
                    } else {
                        containingFile.add(stmt)
                    }
                }
            }
        }
    }

    /**
     * 为 Vue 全局 i18n 实例构造 import 语句。
     *
     * 流程：
     * 1. 通过 Util.findVueI18nInstanceFile 查找 createI18n 调用的文件
     * 2. 通过 resolveVueI18nImportPath 推断别名/相对路径（自动去掉扩展名和 /index 后缀）
     * 3. 通过 isVueI18nDefaultExport 判断命名 or 默认导入语法
     * 4. 任何一步失败都回退到 `import { i18n } from 'vue-i18n'`
     */
    fun buildVueI18nInstanceImport(psiFile: PsiElement): String {
        val containingFile = psiFile.containingFile ?: return I18nProcessor.FALLBACK_VUE_I18N_IMPORT
        val i18nVFile = Util.findVueI18nInstanceFile(containingFile)
            ?: return I18nProcessor.FALLBACK_VUE_I18N_IMPORT
        val importPath = Util.resolveVueI18nImportPath(containingFile, i18nVFile)
            ?: return I18nProcessor.FALLBACK_VUE_I18N_IMPORT
        val isDefault = Util.isVueI18nDefaultExport(i18nVFile)
        return if (isDefault) {
            "import i18n from '$importPath';\n"
        } else {
            "import { i18n } from '$importPath';\n"
        }
    }

    /**
     * 为 React 全局 i18n 实例构造 import 语句（locale 优先，找不到时由调用方回退 getI18n）。
     *
     * 流程：
     * 1. 通过 Util.findReactI18nInstanceFileInRoot 查找"导出了 i18n"的 React 初始化文件
     * 2. 通过 Util.resolveVueI18nImportPath 推断别名/相对路径（自动去掉扩展名和 /index 后缀）
     * 3. 通过 Util.isVueI18nDefaultExport 判断命名 or 默认导入语法
     *
     * 返回 null 代表没有可用的 locale i18n 实例（无初始化文件 / 未导出 i18n / 路径无法推断）。
     */
    fun buildReactI18nInstanceImport(psiFile: PsiElement): String? {
        val containingFile = psiFile.containingFile ?: return null
        val projectRoot = Util.findProjectRoot(containingFile) ?: return null
        val initFile = Util.findReactI18nInstanceFileInRoot(projectRoot) ?: return null
        val importPath = Util.resolveVueI18nImportPath(containingFile, initFile) ?: return null
        return if (Util.isVueI18nDefaultExport(initFile)) {
            "import i18n from '$importPath';\n"
        } else {
            "import { i18n } from '$importPath';\n"
        }
    }

    /**
     * 检查文件是否已导入 i18n 实例（命名导入、默认导入、namespace 导入均可）。
     * 匹配形式：
     * - `import { i18n } from '...'`            （命名导入）
     * - `import i18n from '...'`                （默认导入）
     * - `import * as i18n from '...'`           （namespace 导入）
     * - `import i18n, { other } from '...'`     （混合导入）
     *
     * —— 新规则（React 兼容）：若已存在 `import { getI18n } from 'react-i18next'` 也算
     * "已有全局 i18n 能力"，因为 getI18n() 就是 react-i18next 返回 i18next i18n 实例的
     * 官方 API，不应该重复再注入 `import i18n from 'i18next'`。
     */
    fun hasI18nInstanceImported(root: PsiElement): Boolean {
        if (hasReactGetI18nImported(root)) return true
        val imports = PsiTreeUtil.findChildrenOfType(root, ES6ImportDeclaration::class.java)
        val namedImport = Regex("""import\s*\{[^}]*\bi18n\b[^}]*\}""")
        val defaultImport = Regex("""import\s+i18n\s+(?:,|from)""")
        val namespaceImport = Regex("""import\s+\*\s+as\s+i18n\s+from""")
        return imports.any { imp ->
            namedImport.containsMatchIn(imp.text) ||
                defaultImport.containsMatchIn(imp.text) ||
                namespaceImport.containsMatchIn(imp.text)
        }
    }

    /**
     * 检查文件是否已导入 react-i18next 的 getI18n（React 新模板）。
     * 匹配形式：
     *   - `import { getI18n } from 'react-i18next'`           （独立命名导入）
     *   - `import { useTranslation, getI18n } from ...'`      （和 useTranslation 混合）
     *   - 路径中含 `react-i18next`（容忍引号/反引号差异）
     */
    fun hasReactGetI18nImported(root: PsiElement): Boolean =
        I18nPsiTools.hasReactGetI18nImported(root)

    /** 找到第一个非空白符、非注释的子元素 */
    fun findFirstNonWhitespaceChild(element: PsiElement): PsiElement? =
        I18nPsiTools.findFirstNonWhitespaceChild(element)
}