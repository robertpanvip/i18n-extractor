package com.pan.extractor.planner

import com.pan.extractor.I18nFramework
import com.pan.extractor.ImportManager
import com.pan.extractor.I18nProcessor
import com.intellij.psi.PsiElement

/**
 * Planner 层 —— 把 collect 阶段锁定的注入决策转换为 [ImportPlan]（目标架构 Phase 4）。
 *
 * 核心原则（PROJECT_ANALYSIS §4）：
 * > 分析阶段不修改项目；Plan 阶段只描述修改；Apply 阶段统一提交修改。
 *
 * §11 收敛点：注入决策（要注入哪些 import / setup / hook / 全局别名）已下沉到各自框架策略
 * 的 [ImportBuildStrategy.buildImportPlan]（[com.pan.extractor.VueI18nStrategy] /
 * [ReactI18nextStrategy] / [SolidI18nStrategy] / [GenericStrategy]），这里不再出现
 * `isVue/isReact/isSolid` 三岔——本对象只负责「把决策交给框架，再把返回的 [ImportPlan] 透传」，
 * 并为无有效文件等边界情形返回一个仅描述文件信息的空计划。
 */
object ImportPlanner {

    /**
     * 把 [decision]（对应 collect 阶段锁定的 needInject* 标记）交给 [framework]，
     * 由策略自身产出该文件需要的 [ImportPlan]。
     *
     * @param injector 注入工具：以只读方式传给策略用于去重 / import 文本解析，不写 PSI。
     *                 缺省传 [processor] 的 [I18nProcessor.injector]，行为与旧分支一致。
     * @return 该文件所需的注入计划；[psiFile] 无有效 containingFile 时返回仅描述文件信息的空计划。
     */
    fun buildImportPlan(
        processor: I18nProcessor,
        psiFile: PsiElement,
        framework: I18nFramework,
        decision: ImportManager.InjectionDecision,
        injector: ImportManager = processor.injector,
    ): ImportPlan {
        val file = psiFile.containingFile
            ?: return ImportPlan(fileName = psiFile.javaClass.simpleName, frameworkId = framework.id)
        return framework.buildImportPlan(file, processor.analyzer.tFunctionName, decision, injector)
    }
}