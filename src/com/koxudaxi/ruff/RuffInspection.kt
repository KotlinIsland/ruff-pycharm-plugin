package com.koxudaxi.ruff

import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.inspections.PyInspection
import com.jetbrains.python.inspections.PyInspectionVisitor
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.types.TypeEvalContext

class RuffInspection : PyInspection() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
        session: LocalInspectionToolSession,
    ): PsiElementVisitor = Visitor(holder, PyInspectionVisitor.getContext(session))

    inner class Visitor(holder: ProblemsHolder, context: TypeEvalContext) :
        PyInspectionVisitor(holder, context) {
        private val argsBase =
            listOf("--exit-zero", "--no-cache", "--no-fix", "--format", "json")

        override fun visitPyFile(node: PyFile) {
            super.visitPyFile(node)

            inspectFile(node)
        }

        private fun inspectFile(pyFile: PyFile) {
            if (!pyFile.isApplicableTo) return
            val project = pyFile.project
            val document = PsiDocumentManager.getInstance(project).getDocument(pyFile) ?: return
            executeOnPooledThread(null) {
                val response = runRuff(pyFile, argsBase) ?: return@executeOnPooledThread
                val showRuleCode = RuffConfigService.getInstance(project).showRuleCode

                parseJsonResponse(response).forEach {
                    val psiElement = getPyElement(it, pyFile, document) ?: return@forEach
                    registerProblem(
                        psiElement,
                        if (showRuleCode) "${it.code} ${it.message}" else it.message,
                        it.fix?.let { fix ->
                            RuffQuickFix.create(fix, document)
                        })
                }
            }
        }

        private fun getPyElement(result: Result, pyFile: PyFile, document: Document): PsiElement? {
            document.getStartEndRange(result.location, result.endLocation, -1).let {
                return PsiTreeUtil.findElementOfClassAtRange(
                    pyFile,
                    it.startOffset,
                    it.endOffset,
                    PsiElement::class.java
                )
            }
        }
    }

}