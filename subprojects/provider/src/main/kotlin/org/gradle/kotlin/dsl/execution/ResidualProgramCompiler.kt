/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.execution

import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.hash.HashCode

import org.gradle.kotlin.dsl.KotlinBuildScript
import org.gradle.kotlin.dsl.KotlinInitScript
import org.gradle.kotlin.dsl.KotlinSettingsScript

import org.gradle.kotlin.dsl.execution.ResidualProgram.Dynamic
import org.gradle.kotlin.dsl.execution.ResidualProgram.Instruction
import org.gradle.kotlin.dsl.execution.ResidualProgram.Static

import org.gradle.kotlin.dsl.support.KotlinBuildscriptAndPluginsBlock
import org.gradle.kotlin.dsl.support.KotlinBuildscriptBlock
import org.gradle.kotlin.dsl.support.KotlinInitscriptBlock
import org.gradle.kotlin.dsl.support.KotlinPluginsBlock
import org.gradle.kotlin.dsl.support.KotlinSettingsBuildscriptBlock
import org.gradle.kotlin.dsl.support.compileKotlinScriptToDirectory
import org.gradle.kotlin.dsl.support.messageCollectorFor
import org.gradle.kotlin.dsl.support.unsafeLazy

import org.gradle.plugin.management.internal.DefaultPluginRequests

import org.gradle.plugin.use.internal.PluginRequestCollector

import org.jetbrains.kotlin.script.KotlinScriptDefinition

import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Label
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Opcodes.T_BYTE
import org.jetbrains.org.objectweb.asm.Type

import org.slf4j.Logger

import java.io.File

import kotlin.reflect.KClass

import kotlin.script.dependencies.Environment
import kotlin.script.dependencies.ScriptContents
import kotlin.script.experimental.dependencies.DependenciesResolver
import kotlin.script.experimental.dependencies.ScriptDependencies


/**
 * Compiles the given [residual program][ResidualProgram] to an [ExecutableProgram] subclass named `Program`
 * stored in the given [outputDir].
 */
internal
class ResidualProgramCompiler(
    private val outputDir: File,
    private val classPath: ClassPath = ClassPath.EMPTY,
    private val originalSourceHash: HashCode,
    private val programKind: ProgramKind,
    private val programTarget: ProgramTarget,
    private val implicitImports: List<String> = emptyList(),
    private val logger: Logger = interpreterLogger
) {

    fun compile(program: ResidualProgram) = when (program) {
        is Static -> emitStaticProgram(program)
        is Dynamic -> emitDynamicProgram(program)
    }

    private
    fun emitStaticProgram(program: Static) {

        program<ExecutableProgram> {

            overrideExecute {
                emit(program.instructions)
            }
        }
    }

    private
    fun emitDynamicProgram(program: Dynamic) {

        val scriptSource = program.source
        val originalScriptPath = scriptSource.path
        val scriptFile = scriptFileFor(scriptSource, "stage-2")
        val sourceFilePath = scriptFile.canonicalPath

        program<ExecutableProgram.StagedProgram> {

            overrideExecute {

                emit(program.prelude.instructions)
                emitEvaluateSecondStageOf()
            }

            overrideLoadSecondStageFor(sourceFilePath, originalScriptPath)
        }
    }

    private
    fun MethodVisitor.emit(instructions: List<Instruction>) {
        instructions.forEach {
            emit(it)
        }
    }

    private
    fun MethodVisitor.emit(instruction: Instruction) = when (instruction) {
        is Instruction.SetupEmbeddedKotlin -> emitSetupEmbeddedKotlinFor()
        is Instruction.CloseTargetScope -> emitCloseTargetScopeOf()
        is Instruction.Eval -> emitEval(instruction.script)
        is Instruction.ApplyBasePlugins -> emitApplyBasePluginsTo()
        is Instruction.ApplyDefaultPluginRequests -> emitApplyEmptyPluginRequestsTo()
        is Instruction.ApplyPluginRequestsOf -> {
            val program = instruction.program
            when (program) {
                is Program.Plugins -> emitPrecompiledPluginsBlock(program)
                is Program.Stage1Sequence -> emitStage1Sequence(program.buildscript, program.plugins)
                else -> throw IllegalStateException("Expecting a residual program with plugins, got `$program'")
            }
        }
    }

    private
    fun MethodVisitor.emitSetupEmbeddedKotlinFor() {
        // programHost.setupEmbeddedKotlinFor(scriptHost)
        ALOAD(Vars.ProgramHost)
        ALOAD(Vars.ScriptHost)
        invokeHost("setupEmbeddedKotlinFor", kotlinScriptHostToVoid)
    }

    private
    fun MethodVisitor.emitEval(source: ProgramSource) {
        val precompiledScriptClass = compileStage1(source, stage1ScriptDefinition)
        emitInstantiationOfPrecompiledScriptClass(precompiledScriptClass)
    }

    private
    fun MethodVisitor.emitStage1Sequence(buildscript: Program.Buildscript, plugins: Program.Plugins) {

        val precompiledBuildscriptWithPluginsBlock =
            compileStage1(
                plugins.fragment.source.map {
                    it.preserve(
                        buildscript.fragment.section.wholeRange,
                        plugins.fragment.section.wholeRange)
                },
                buildscriptWithPluginsScriptDefinition)

        precompiledScriptClassInstantiation(precompiledBuildscriptWithPluginsBlock) {

            emitPluginRequestCollectorInstantiation()

            NEW(precompiledBuildscriptWithPluginsBlock)
            ALOAD(Vars.ScriptHost)
            // ${plugins}(temp.createSpec(lineNumber))
            emitPluginRequestCollectorCreateSpecFor(plugins)
            INVOKESPECIAL(
                precompiledBuildscriptWithPluginsBlock,
                "<init>",
                "(Lorg/gradle/kotlin/dsl/support/KotlinScriptHost;Lorg/gradle/plugin/use/PluginDependenciesSpec;)V")

            emitApplyPluginsTo()
        }
    }

    /**
     * programHost.applyPluginsTo(scriptHost, collector.getPluginRequests())
     */
    private
    fun MethodVisitor.emitApplyPluginsTo() {
        ALOAD(Vars.ProgramHost)
        ALOAD(Vars.ScriptHost)
        emitPluginRequestCollectorGetPluginRequests()
        invokeApplyPluginsTo()
    }

    private
    fun MethodVisitor.emitApplyBasePluginsTo() {
        ALOAD(Vars.ProgramHost)
        ALOAD(Vars.ScriptHost)
        INVOKEVIRTUAL(
            "org/gradle/kotlin/dsl/support/KotlinScriptHost",
            "getTarget",
            "()Ljava/lang/Object;")
        CHECKCAST("org/gradle/api/Project")
        invokeHost(
            "applyBasePluginsTo",
            "(Lorg/gradle/api/Project;)V")
    }

    private
    fun MethodVisitor.emitApplyEmptyPluginRequestsTo() {
        ALOAD(Vars.ProgramHost)
        ALOAD(Vars.ScriptHost)
        GETSTATIC(
            DefaultPluginRequests::class.internalName,
            "EMPTY",
            "Lorg/gradle/plugin/management/internal/PluginRequests;")
        invokeApplyPluginsTo()
    }

    fun emitStage2ProgramFor(scriptFile: File, originalPath: String) {

        val precompiledScriptClass = compileScript(scriptFile, originalPath, stage2ScriptDefinition)

        program<ExecutableProgram> {

            overrideExecute {

                emitInstantiationOfPrecompiledScriptClass(precompiledScriptClass)
            }
        }
    }

    private
    fun MethodVisitor.emitPrecompiledPluginsBlock(program: Program.Plugins) {

        val precompiledPluginsBlock = compilePlugins(program)

        precompiledScriptClassInstantiation(precompiledPluginsBlock) {

            // val collector = PluginRequestCollector(scriptSource)
            emitPluginRequestCollectorInstantiation()

            // ${precompiledPluginsBlock}(collector.createSpec(lineNumber))
            NEW(precompiledPluginsBlock)
            emitPluginRequestCollectorCreateSpecFor(program)
            INVOKESPECIAL(
                precompiledPluginsBlock,
                "<init>",
                "(Lorg/gradle/plugin/use/PluginDependenciesSpec;)V")

            emitApplyPluginsTo()
        }
    }

    /**
     * val collector = PluginRequestCollector(scriptSource)
     */
    private
    fun MethodVisitor.emitPluginRequestCollectorInstantiation() {
        NEW(pluginRequestCollectorType)
        DUP()
        ALOAD(Vars.ScriptHost)
        INVOKEVIRTUAL(
            "org/gradle/kotlin/dsl/support/KotlinScriptHost",
            "getScriptSource",
            "()Lorg/gradle/groovy/scripts/ScriptSource;")
        INVOKESPECIAL(
            pluginRequestCollectorType,
            "<init>",
            "(Lorg/gradle/groovy/scripts/ScriptSource;)V")
        ASTORE(Vars.PluginRequestCollector)
    }

    private
    fun MethodVisitor.emitPluginRequestCollectorGetPluginRequests() {
        ALOAD(Vars.PluginRequestCollector)
        INVOKEVIRTUAL(
            pluginRequestCollectorType,
            "getPluginRequests",
            "()Lorg/gradle/plugin/management/internal/PluginRequests;")
    }

    private
    fun MethodVisitor.emitPluginRequestCollectorCreateSpecFor(plugins: Program.Plugins) {
        ALOAD(Vars.PluginRequestCollector)
        LDC(plugins.fragment.lineNumber)
        INVOKEVIRTUAL(
            pluginRequestCollectorType,
            "createSpec",
            "(I)Lorg/gradle/plugin/use/PluginDependenciesSpec;")
    }

    private
    val pluginRequestCollectorType by unsafeLazy { PluginRequestCollector::class.internalName }

    private
    fun MethodVisitor.invokeApplyPluginsTo() {
        invokeHost(
            "applyPluginsTo",
            "(Lorg/gradle/kotlin/dsl/support/KotlinScriptHost;Lorg/gradle/plugin/management/internal/PluginRequests;)V")
    }

    private
    fun ClassWriter.overrideLoadSecondStageFor(sourceFilePath: String, originalPath: String) {
        publicMethod(
            "loadSecondStageFor",
            "(" +
                "Lorg/gradle/kotlin/dsl/execution/ExecutableProgram\$Host;" +
                "Lorg/gradle/kotlin/dsl/support/KotlinScriptHost;" +
                "Ljava/lang/String;" +
                "Lorg/gradle/internal/hash/HashCode;" +
                "Lorg/gradle/internal/classpath/ClassPath;" +
                ")Ljava/lang/Class;",
            "(" +
                "Lorg/gradle/kotlin/dsl/execution/ExecutableProgram\$Host;" +
                "Lorg/gradle/kotlin/dsl/support/KotlinScriptHost<*>;" +
                "Ljava/lang/String;" +
                "Lorg/gradle/internal/hash/HashCode;" +
                "Lorg/gradle/internal/classpath/ClassPath;" +
                ")Ljava/lang/Class<*>;"
        ) {

            emitCompileSecondStageScript(sourceFilePath, originalPath)
            ARETURN()
        }
    }

    private
    fun MethodVisitor.emitCompileSecondStageScript(sourceFilePath: String, originalScriptPath: String) {
        ALOAD(Vars.ProgramHost)
        LDC(sourceFilePath)
        LDC(originalScriptPath)
        ALOAD(Vars.ScriptHost)
        ALOAD(3)
        ALOAD(4)
        GETSTATIC(programKind)
        GETSTATIC(programTarget)
        ALOAD(5) // accessorsClassPath
        invokeHost(
            ExecutableProgram.Host::compileSecondStageScript.name,
            "(" +
                "Ljava/lang/String;Ljava/lang/String;" +
                "Lorg/gradle/kotlin/dsl/support/KotlinScriptHost;" +
                "Ljava/lang/String;Lorg/gradle/internal/hash/HashCode;" +
                "Lorg/gradle/kotlin/dsl/execution/ProgramKind;" +
                "Lorg/gradle/kotlin/dsl/execution/ProgramTarget;" +
                "Lorg/gradle/internal/classpath/ClassPath;" +
                ")Ljava/lang/Class;")
    }

    private
    fun MethodVisitor.emitEvaluateSecondStageOf() {
        // programHost.evaluateSecondStageOf(...)
        ALOAD(Vars.ProgramHost)
        ALOAD(Vars.Program)
        ALOAD(Vars.ScriptHost)
        LDC(programTarget.name + "/" + programKind.name + "/stage2")
        // Move HashCode value to a static field so it's cached across invocations
        loadHashCode(originalSourceHash)
        if (requiresAccessors()) emitAccessorsClassPathForScriptHost() else ACONST_NULL()
        invokeHost(
            ExecutableProgram.Host::evaluateSecondStageOf.name,
            "(" +
                "Lorg/gradle/kotlin/dsl/execution/ExecutableProgram\$StagedProgram;" +
                "Lorg/gradle/kotlin/dsl/support/KotlinScriptHost;" +
                "Ljava/lang/String;" +
                "Lorg/gradle/internal/hash/HashCode;" +
                "Lorg/gradle/internal/classpath/ClassPath;" +
                ")V")
    }

    private
    fun requiresAccessors() =
        programTarget == ProgramTarget.Project && programKind == ProgramKind.TopLevel

    private
    fun MethodVisitor.emitAccessorsClassPathForScriptHost() {
        ALOAD(Vars.ProgramHost)
        ALOAD(Vars.ScriptHost)
        invokeHost(
            "accessorsClassPathFor",
            "(Lorg/gradle/kotlin/dsl/support/KotlinScriptHost;)Lorg/gradle/internal/classpath/ClassPath;"
        )
    }

    private
    fun ClassVisitor.overrideExecute(methodBody: MethodVisitor.() -> Unit) {
        publicMethod("execute", programHostToKotlinScriptHostToVoid, "(Lorg/gradle/kotlin/dsl/execution/ExecutableProgram\$Host;Lorg/gradle/kotlin/dsl/support/KotlinScriptHost<*>;)V") {
            methodBody()
            RETURN()
        }
    }

    private
    fun compilePlugins(program: Program.Plugins) =
        compileStage1(
            program.fragment.source.map { it.preserve(program.fragment.section.wholeRange) },
            pluginsScriptDefinition)

    private
    fun MethodVisitor.loadHashCode(hashCode: HashCode) {
        loadByteArray(hashCode.toByteArray())
        INVOKESTATIC(
            HashCode::class.internalName,
            "fromBytes",
            "([B)Lorg/gradle/internal/hash/HashCode;")
    }

    private
    fun MethodVisitor.emitInstantiationOfPrecompiledScriptClass(precompiledScriptClass: String) {

        precompiledScriptClassInstantiation(precompiledScriptClass) {

            // ${precompiledScriptClass}(scriptHost)
            NEW(precompiledScriptClass)
            ALOAD(Vars.ScriptHost)
            INVOKESPECIAL(precompiledScriptClass, "<init>", kotlinScriptHostToVoid)
        }
    }

    private
    fun MethodVisitor.precompiledScriptClassInstantiation(precompiledScriptClass: String, instantiation: MethodVisitor.() -> Unit) {

        TRY_CATCH<Throwable>(
            tryBlock = {

                instantiation()
            },
            catchBlock = {

                emitOnScriptException(precompiledScriptClass)
            })
    }

    private
    fun MethodVisitor.emitOnScriptException(precompiledScriptClass: String) {
        // Exception is on the stack
        ASTORE(4)
        ALOAD(Vars.ProgramHost)
        ALOAD(4)
        LDC(Type.getType("L$precompiledScriptClass;"))
        ALOAD(Vars.ScriptHost)
        invokeHost(
            "handleScriptException",
            "(Ljava/lang/Throwable;Ljava/lang/Class;Lorg/gradle/kotlin/dsl/support/KotlinScriptHost;)V")
    }

    private
    fun MethodVisitor.emitCloseTargetScopeOf() {
        // programHost.closeTargetScopeOf(scriptHost)
        ALOAD(Vars.ProgramHost)
        ALOAD(Vars.ScriptHost)
        invokeHost("closeTargetScopeOf", kotlinScriptHostToVoid)
    }

    private
    fun MethodVisitor.invokeHost(name: String, desc: String) {
        INVOKEINTERFACE(ExecutableProgram.Host::class.internalName, name, desc)
    }

    private
    object Vars {

        const val Program = 0

        const val ProgramHost = 1

        const val ScriptHost = 2

        // Only valid within the context of `overrideExecute`
        const val PluginRequestCollector = 3
    }

    private
    val programHostToKotlinScriptHostToVoid =
        "(Lorg/gradle/kotlin/dsl/execution/ExecutableProgram\$Host;Lorg/gradle/kotlin/dsl/support/KotlinScriptHost;)V"

    private
    val kotlinScriptHostToVoid =
        "(Lorg/gradle/kotlin/dsl/support/KotlinScriptHost;)V"

    private
    inline fun <reified T : ExecutableProgram> program(noinline classBody: ClassWriter.() -> Unit = {}) {
        program(T::class.internalName, classBody)
    }

    private
    fun program(superName: String, classBody: ClassWriter.() -> Unit = {}) {
        writeFile("Program.class",
            publicClass("Program", superName, null) {
                publicDefaultConstructor(superName)
                classBody()
            })
    }

    private
    fun writeFile(relativePath: String, bytes: ByteArray) {
        outputFile(relativePath).writeBytes(bytes)
    }

    private
    fun outputFile(relativePath: String) =
        outputDir.resolve(relativePath)

    private
    fun compileStage1(source: ProgramSource, scriptDefinition: KotlinScriptDefinition): String {
        val scriptFile = scriptFileFor(source, "stage-1")
        val originalScriptPath = source.path
        return compileScript(scriptFile, originalScriptPath, scriptDefinition)
    }

    private
    fun compileScript(scriptFile: File, originalPath: String, scriptDefinition: KotlinScriptDefinition): String =
        compileKotlinScriptToDirectory(
            outputDir,
            scriptFile,
            scriptDefinition,
            classPath.asFiles,
            messageCollectorFor(logger) { path ->
                if (path == scriptFile.path) originalPath
                else path
            })

    private
    fun scriptFileFor(source: ProgramSource, stage: String) =
        uniqueScriptFileFor(source.path, stage).apply {
            writeText(source.text)
        }

    private
    fun uniqueScriptFileFor(sourcePath: String, stage: String) =
        outputDir
            .resolve(stage)
            .apply { mkdirs() }
            .resolve(scriptFileNameFor(sourcePath))

    private
    fun scriptFileNameFor(scriptPath: String) = scriptPath.run {
        val index = lastIndexOf('/')
        if (index != -1) substring(index + 1, length) else substringAfterLast('\\')
    }

    private
    val stage1ScriptDefinition
        get() = scriptDefinitionFromTemplate(
            when (programTarget) {
                ProgramTarget.Project -> KotlinBuildscriptBlock::class
                ProgramTarget.Settings -> KotlinSettingsBuildscriptBlock::class
                ProgramTarget.Gradle -> KotlinInitscriptBlock::class
            })

    private
    val stage2ScriptDefinition
        get() = scriptDefinitionFromTemplate(
            when (programTarget) {
                ProgramTarget.Project -> KotlinBuildScript::class
                ProgramTarget.Settings -> KotlinSettingsScript::class
                ProgramTarget.Gradle -> KotlinInitScript::class
            })

    private
    val pluginsScriptDefinition
        get() = scriptDefinitionFromTemplate(KotlinPluginsBlock::class)

    private
    val buildscriptWithPluginsScriptDefinition
        get() = scriptDefinitionFromTemplate(KotlinBuildscriptAndPluginsBlock::class)

    private
    fun scriptDefinitionFromTemplate(template: KClass<out Any>) =

        object : KotlinScriptDefinition(template), DependenciesResolver {

            override val dependencyResolver = this

            override fun resolve(
                scriptContents: ScriptContents,
                environment: Environment
            ): DependenciesResolver.ResolveResult = DependenciesResolver.ResolveResult.Success(
                ScriptDependencies(imports = implicitImports),
                emptyList()
            )
        }
}


private
fun publicClass(name: String, superName: String = "java/lang/Object", interfaces: Array<String>? = null, classBody: ClassWriter.() -> Unit = {}) =
    ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES).run {
        visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, interfaces)
        classBody()
        visitEnd()
        toByteArray()
    }


private
fun ClassWriter.publicDefaultConstructor(superName: String) {
    publicMethod("<init>", "()V") {
        ALOAD(0)
        INVOKESPECIAL(superName, "<init>", "()V")
        RETURN()
    }
}


private
fun ClassVisitor.publicMethod(
    name: String,
    desc: String,
    signature: String? = null,
    exceptions: Array<String>? = null,
    methodBody: MethodVisitor.() -> Unit
) {
    visitMethod(Opcodes.ACC_PUBLIC, name, desc, signature, exceptions).apply {
        visitCode()
        methodBody()
        visitMaxs(0, 0)
        visitEnd()
    }
}


private
fun MethodVisitor.loadByteArray(byteArray: ByteArray) {
    LDC(byteArray.size)
    NEWARRAY(T_BYTE)
    for ((i, byte) in byteArray.withIndex()) {
        DUP()
        LDC(i)
        LDC(byte)
        BASTORE()
    }
}


private
fun MethodVisitor.NEW(type: String) {
    visitTypeInsn(Opcodes.NEW, type)
}


private
fun MethodVisitor.NEWARRAY(primitiveType: Int) {
    visitIntInsn(Opcodes.NEWARRAY, primitiveType)
}


private
fun MethodVisitor.LDC(value: Any) {
    visitLdcInsn(value)
}


private
fun MethodVisitor.INVOKEVIRTUAL(owner: String, name: String, desc: String, itf: Boolean = false) {
    visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, desc, itf)
}


private
fun MethodVisitor.INVOKESPECIAL(owner: String, name: String, desc: String, itf: Boolean = false) {
    visitMethodInsn(Opcodes.INVOKESPECIAL, owner, name, desc, itf)
}


private
fun MethodVisitor.INVOKEINTERFACE(owner: String, name: String, desc: String, itf: Boolean = true) {
    visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, name, desc, itf)
}


private
fun MethodVisitor.INVOKESTATIC(owner: String, name: String, desc: String) {
    visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false)
}


private
fun MethodVisitor.BASTORE() {
    visitInsn(Opcodes.BASTORE)
}


private
fun MethodVisitor.DUP() {
    visitInsn(Opcodes.DUP)
}


private
fun MethodVisitor.ARETURN() {
    visitInsn(Opcodes.ARETURN)
}


private
fun MethodVisitor.RETURN() {
    visitInsn(Opcodes.RETURN)
}


private
fun MethodVisitor.ALOAD(`var`: Int) {
    visitVarInsn(Opcodes.ALOAD, `var`)
}


private
fun MethodVisitor.ASTORE(`var`: Int) {
    visitVarInsn(Opcodes.ASTORE, `var`)
}


private
fun MethodVisitor.GOTO(label: Label) {
    visitJumpInsn(Opcodes.GOTO, label)
}


private
inline fun <reified T> MethodVisitor.TRY_CATCH(
    noinline tryBlock: MethodVisitor.() -> Unit,
    noinline catchBlock: MethodVisitor.() -> Unit
) =
    TRY_CATCH(T::class.internalName, tryBlock, catchBlock)


private
fun MethodVisitor.TRY_CATCH(
    exceptionType: String,
    tryBlock: MethodVisitor.() -> Unit,
    catchBlock: MethodVisitor.() -> Unit
) {

    val tryBlockStart = Label()
    val tryBlockEnd = Label()
    val catchBlockStart = Label()
    val catchBlockEnd = Label()
    visitTryCatchBlock(tryBlockStart, tryBlockEnd, catchBlockStart, exceptionType)

    visitLabel(tryBlockStart)
    tryBlock()
    GOTO(catchBlockEnd)
    visitLabel(tryBlockEnd)

    visitLabel(catchBlockStart)
    catchBlock()
    visitLabel(catchBlockEnd)
}


private
fun <T : Enum<T>> MethodVisitor.GETSTATIC(field: T) {
    val owner = field.declaringClass.internalName
    GETSTATIC(owner, field.name, "L$owner;")
}


private
fun MethodVisitor.GETSTATIC(owner: String, name: String, desc: String) {
    visitFieldInsn(Opcodes.GETSTATIC, owner, name, desc)
}


private
fun MethodVisitor.CHECKCAST(type: String) {
    visitTypeInsn(Opcodes.CHECKCAST, type)
}


private
fun MethodVisitor.ACONST_NULL() {
    visitInsn(Opcodes.ACONST_NULL)
}


private
val KClass<*>.internalName: String
    get() = java.internalName


private
inline val Class<*>.internalName: String
    get() = Type.getInternalName(this)
