/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003-2007 University of Maryland
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.detect;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.meta.When;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.bcel.Const;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.CodeException;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.LineNumberTable;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ATHROW;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.IFNONNULL;
import org.apache.bcel.generic.IFNULL;
import org.apache.bcel.generic.INVOKEDYNAMIC;
import org.apache.bcel.generic.INVOKEINTERFACE;
import org.apache.bcel.generic.INVOKEVIRTUAL;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.InstructionTargeter;
import org.apache.bcel.generic.InvokeInstruction;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.PUTFIELD;
import org.apache.bcel.generic.ReturnInstruction;
import org.objectweb.asm.Type;

import edu.umd.cs.findbugs.BugAccumulator;
import edu.umd.cs.findbugs.BugAnnotation;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.Detector;
import edu.umd.cs.findbugs.FieldAnnotation;
import edu.umd.cs.findbugs.FindBugsAnalysisFeatures;
import edu.umd.cs.findbugs.LocalVariableAnnotation;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.UseAnnotationDatabase;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.BasicBlock;
import edu.umd.cs.findbugs.ba.CFG;
import edu.umd.cs.findbugs.ba.CFGBuilderException;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.DataflowAnalysisException;
import edu.umd.cs.findbugs.ba.Edge;
import edu.umd.cs.findbugs.ba.Hierarchy;
import edu.umd.cs.findbugs.ba.INullnessAnnotationDatabase;
import edu.umd.cs.findbugs.ba.JavaClassAndMethod;
import edu.umd.cs.findbugs.ba.Location;
import edu.umd.cs.findbugs.ba.MissingClassException;
import edu.umd.cs.findbugs.ba.NullnessAnnotation;
import edu.umd.cs.findbugs.ba.OpcodeStackScanner;
import edu.umd.cs.findbugs.ba.SignatureConverter;
import edu.umd.cs.findbugs.ba.SignatureParser;
import edu.umd.cs.findbugs.ba.XFactory;
import edu.umd.cs.findbugs.ba.XField;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.ba.XMethodParameter;
import edu.umd.cs.findbugs.ba.interproc.ParameterProperty;
import edu.umd.cs.findbugs.ba.jsr305.TypeQualifierAnnotation;
import edu.umd.cs.findbugs.ba.jsr305.TypeQualifierApplications;
import edu.umd.cs.findbugs.ba.jsr305.TypeQualifierValue;
import edu.umd.cs.findbugs.ba.npe.IsNullValue;
import edu.umd.cs.findbugs.ba.npe.IsNullValueDataflow;
import edu.umd.cs.findbugs.ba.npe.IsNullValueFrame;
import edu.umd.cs.findbugs.ba.npe.NullDerefAndRedundantComparisonCollector;
import edu.umd.cs.findbugs.ba.npe.NullDerefAndRedundantComparisonFinder;
import edu.umd.cs.findbugs.ba.npe.NullValueUnconditionalDeref;
import edu.umd.cs.findbugs.ba.npe.ParameterNullnessPropertyDatabase;
import edu.umd.cs.findbugs.ba.npe.PointerUsageRequiringNonNullValue;
import edu.umd.cs.findbugs.ba.npe.RedundantBranch;
import edu.umd.cs.findbugs.ba.npe.ReturnPathType;
import edu.umd.cs.findbugs.ba.npe.ReturnPathTypeDataflow;
import edu.umd.cs.findbugs.ba.npe.TypeQualifierNullnessAnnotationDatabase;
import edu.umd.cs.findbugs.ba.npe.UsagesRequiringNonNullValues;
import edu.umd.cs.findbugs.ba.type.TypeDataflow;
import edu.umd.cs.findbugs.ba.type.TypeFrame;
import edu.umd.cs.findbugs.ba.vna.ValueNumber;
import edu.umd.cs.findbugs.ba.vna.ValueNumberDataflow;
import edu.umd.cs.findbugs.ba.vna.ValueNumberFrame;
import edu.umd.cs.findbugs.ba.vna.ValueNumberSourceInfo;
import edu.umd.cs.findbugs.bcel.generic.NullnessConversationInstruction;
import edu.umd.cs.findbugs.classfile.CheckedAnalysisException;
import edu.umd.cs.findbugs.classfile.ClassDescriptor;
import edu.umd.cs.findbugs.classfile.DescriptorFactory;
import edu.umd.cs.findbugs.classfile.Global;
import edu.umd.cs.findbugs.classfile.analysis.AnnotationValue;
import edu.umd.cs.findbugs.internalAnnotations.StaticConstant;
import edu.umd.cs.findbugs.log.Profiler;
import edu.umd.cs.findbugs.props.GeneralWarningProperty;
import edu.umd.cs.findbugs.props.WarningProperty;
import edu.umd.cs.findbugs.props.WarningPropertySet;
import edu.umd.cs.findbugs.props.WarningPropertyUtil;
import edu.umd.cs.findbugs.util.Values;
import edu.umd.cs.findbugs.visitclass.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Detector to find instructions where a NullPointerException might be raised.
 * We also look for useless reference comparisons involving null and non-null
 * values.
 *
 * @author David Hovemeyer
 * @author William Pugh
 * @see edu.umd.cs.findbugs.ba.npe.IsNullValueAnalysis
 */
public class FindNullDeref implements Detector, UseAnnotationDatabase, NullDerefAndRedundantComparisonCollector {
    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final boolean DEBUG = SystemProperties.getBoolean("fnd.debug");

    private static final boolean DEBUG_NULLARG = SystemProperties.getBoolean("fnd.debug.nullarg");

    private static final boolean DEBUG_NULLRETURN = SystemProperties.getBoolean("fnd.debug.nullreturn");

    private static final boolean MARK_DOOMED = SystemProperties.getBoolean("fnd.markdoomed", true);

    private static final String METHOD_NAME = SystemProperties.getProperty("fnd.method");

    private static final String CLASS = SystemProperties.getProperty("fnd.class");

    // Fields
    private final BugReporter bugReporter;

    private final BugAccumulator bugAccumulator;

    // Cached database stuff
    private ParameterNullnessPropertyDatabase unconditionalDerefParamDatabase;

    private boolean checkedDatabases = false;

    // Transient state
    private ClassContext classContext;

    private Method method;

    private IsNullValueDataflow invDataflow;

    private ValueNumberDataflow vnaDataflow;

    private BitSet previouslyDeadBlocks;

    private NullnessAnnotation methodAnnotation;

    public FindNullDeref(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
        this.bugAccumulator = new BugAccumulator(bugReporter);
    }

    @Override
    public void visitClassContext(ClassContext classContext) {
        this.classContext = classContext;

        String currentMethod = null;

        JavaClass jclass = classContext.getJavaClass();
        String className = jclass.getClassName();
        if (CLASS != null && !className.equals(CLASS)) {
            return;
        }

        List<Method> methodsInCallOrder = classContext.getMethodsInCallOrder();
        for (Method m : methodsInCallOrder) {
            try {
                if (m.isAbstract() || m.isNative() || m.getCode() == null) {
                    continue;
                }

                currentMethod = SignatureConverter.convertMethodSignature(jclass, m);

                if (METHOD_NAME != null && !m.getName().equals(METHOD_NAME)) {
                    continue;
                }
                if (DEBUG || DEBUG_NULLARG) {
                    System.out.println("Checking for NP in " + currentMethod);
                }
                analyzeMethod(classContext, m);
            } catch (MissingClassException e) {
                bugReporter.reportMissingClass(e.getClassNotFoundException());
            } catch (DataflowAnalysisException e) {
                bugReporter.logError("While analyzing " + currentMethod + ": FindNullDeref caught dae exception", e);
            } catch (CFGBuilderException e) {
                bugReporter.logError("While analyzing " + currentMethod + ": FindNullDeref caught cfgb exception", e);
            }
            bugAccumulator.reportAccumulatedBugs();
        }
    }

    private void analyzeMethod(ClassContext classContext, Method method) throws DataflowAnalysisException, CFGBuilderException {
        if (DEBUG || DEBUG_NULLARG) {
            System.out.println("Pre FND ");
        }

        if ((method.getAccessFlags() & Const.ACC_BRIDGE) != 0) {
            return;
        }

        MethodGen methodGen = classContext.getMethodGen(method);

        if (methodGen == null) {
            return;
        }
        if (!checkedDatabases) {
            checkDatabases();
            checkedDatabases = true;
        }

        XMethod xMethod = XFactory.createXMethod(classContext.getJavaClass(), method);

        // For junit 4 only (does not apply to junit 5)
        ClassDescriptor junitTestAnnotation = DescriptorFactory.createClassDescriptor("org/junit/Test");
        AnnotationValue av = xMethod.getAnnotation(junitTestAnnotation);
        if (av != null) {
            Object value = av.getValue("expected");

            if (value instanceof Type) {
                String className = ((Type) value).getClassName();
                if ("java.lang.NullPointerException".equals(className)) {
                    return;
                }
            }
        }

        // UsagesRequiringNonNullValues uses = classContext.getUsagesRequiringNonNullValues(method);
        this.method = method;
        this.methodAnnotation = getMethodNullnessAnnotation();

        if (DEBUG || DEBUG_NULLARG) {
            System.out.println("FND: " + SignatureConverter.convertMethodSignature(methodGen));
        }

        this.previouslyDeadBlocks = findPreviouslyDeadBlocks();

        // Get the IsNullValueDataflow for the method from the ClassContext
        invDataflow = classContext.getIsNullValueDataflow(method);

        vnaDataflow = classContext.getValueNumberDataflow(method);

        // Create a NullDerefAndRedundantComparisonFinder object to do the actual
        // work. It will call back to report null derefs and redundant null comparisons
        // through the NullDerefAndRedundantComparisonCollector interface we implement.
        NullDerefAndRedundantComparisonFinder worker = new NullDerefAndRedundantComparisonFinder(classContext, method, this);
        worker.execute();

        checkCallSitesAndReturnInstructions();
    }

    /**
     * Find set of blocks which were known to be dead before doing the null
     * pointer analysis.
     *
     * @return set of previously dead blocks, indexed by block id
     * @throws CFGBuilderException
     * @throws DataflowAnalysisException
     */
    private BitSet findPreviouslyDeadBlocks() throws DataflowAnalysisException, CFGBuilderException {
        BitSet deadBlocks = new BitSet();
        ValueNumberDataflow valueNumberDataflow = classContext.getValueNumberDataflow(method);
        for (Iterator<BasicBlock> i = valueNumberDataflow.getCFG().blockIterator(); i.hasNext();) {
            BasicBlock block = i.next();
            ValueNumberFrame vnaFrame = valueNumberDataflow.getStartFact(block);
            if (vnaFrame.isTop()) {
                deadBlocks.set(block.getLabel());
            }
        }

        return deadBlocks;
    }

    /**
     * Check whether the various interprocedural databases we can use
     * exist and are nonempty.
     */
    private void checkDatabases() {
        AnalysisContext analysisContext = AnalysisContext.currentAnalysisContext();
        unconditionalDerefParamDatabase = analysisContext.getUnconditionalDerefParamDatabase();
    }

    /**
     * See if the currently-visited method declares a
     *
     * @NonNull annotation, or overrides a method which declares a
     * @NonNull annotation.
     */
    private NullnessAnnotation getMethodNullnessAnnotation() {

        if (method.getSignature().contains(")L") || method.getSignature().contains(")[")) {
            if (DEBUG_NULLRETURN) {
                System.out.println("Checking return annotation for "
                        + SignatureConverter.convertMethodSignature(classContext.getJavaClass(), method));
            }

            XMethod m = XFactory.createXMethod(classContext.getJavaClass(), method);
            return AnalysisContext.currentAnalysisContext().getNullnessAnnotationDatabase().getResolvedAnnotation(m, false);
        }
        return NullnessAnnotation.UNKNOWN_NULLNESS;
    }

    static class CheckCallSitesAndReturnInstructions {
    }

    private void checkCallSitesAndReturnInstructions() {
        Profiler profiler = Global.getAnalysisCache().getProfiler();
        profiler.start(CheckCallSitesAndReturnInstructions.class);
        try {
            ConstantPoolGen cpg = classContext.getConstantPoolGen();
            TypeDataflow typeDataflow = classContext.getTypeDataflow(method);

            for (Iterator<Location> i = classContext.getCFG(method).locationIterator(); i.hasNext();) {
                Location location = i.next();
                Instruction ins = location.getHandle().getInstruction();
                try {
                    ValueNumberFrame vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
                    if (!vnaFrame.isValid()) {
                        continue;
                    }

                    if (ins instanceof InvokeInstruction) {
                        examineCallSite(location, cpg, typeDataflow);
                    } else if (methodAnnotation == NullnessAnnotation.NONNULL && ins.getOpcode() == Const.ARETURN) {
                        examineReturnInstruction(location);
                    } else if (ins instanceof PUTFIELD) {
                        examinePutfieldInstruction(location, (PUTFIELD) ins, cpg);
                    }
                } catch (ClassNotFoundException e) {
                    bugReporter.reportMissingClass(e);
                }
            }
        } catch (CheckedAnalysisException e) {
            AnalysisContext.logError("error:", e);
        } finally {
            profiler.end(CheckCallSitesAndReturnInstructions.class);
        }
    }

    private void examineCallSite(Location location, ConstantPoolGen cpg, TypeDataflow typeDataflow)
            throws DataflowAnalysisException, CFGBuilderException, ClassNotFoundException {

        InvokeInstruction invokeInstruction = (InvokeInstruction) location.getHandle().getInstruction();

        String methodName = invokeInstruction.getName(cpg);
        String signature = invokeInstruction.getSignature(cpg);

        // Don't check equals() calls.
        // If an equals() call unconditionally dereferences the parameter,
        // it is the fault of the method, not the caller.
        if ("equals".equals(methodName) && "(Ljava/lang/Object;)Z".equals(signature)) {
            return;
        }

        int returnTypeStart = signature.indexOf(')');
        if (returnTypeStart < 0) {
            return;
        }
        String paramList = signature.substring(0, returnTypeStart + 1);

        if ("()".equals(paramList) || (paramList.indexOf('L') < 0 && paramList.indexOf('[') < 0)) {
            // Method takes no arguments, or takes no reference arguments
            return;
        }

        // See if any null arguments are passed
        IsNullValueFrame frame = classContext.getIsNullValueDataflow(method).getFactAtLocation(location);
        if (!frame.isValid()) {
            return;
        }
        /*
        if (false && methodName.equals("checkNotNull")
                && invokeInstruction.getClassName(cpg).equals("com.google.common.base.Preconditions")) {
            SignatureParser sigParser = new SignatureParser(signature);
            int numParameters = sigParser.getNumParameters();
            IsNullValue value = frame.getArgument(invokeInstruction, cpg, 0, sigParser);
            if (value.isDefinitelyNotNull()) {
                TypeFrame typeFrame = typeDataflow.getFactAtLocation(location);
                int priority = NORMAL_PRIORITY;
                String pattern = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE";
                ValueNumberFrame vnFrame = vnaDataflow.getFactAtLocation(location);
                ValueNumber valueNumber = vnFrame.getArgument(invokeInstruction, cpg, 0, sigParser);
                org.apache.bcel.generic.Type typeOfFirstArgument = typeFrame.getArgument(invokeInstruction, cpg, 0, sigParser);
                String signature2 = typeOfFirstArgument.getSignature();
                boolean constantStringForFirstArgument = signature2.equals("Ljava/lang/String;")
                        && valueNumber.hasFlag(ValueNumber.CONSTANT_VALUE);
                @CheckForNull
                BugAnnotation annotation = BugInstance.getSourceForStackValue(classContext, method, location, numParameters - 1);
                if (value.wouldHaveBeenAKaboom()) {
                    pattern = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE";
                    priority = HIGH_PRIORITY;
                } else if (constantStringForFirstArgument) {
                    annotation = null;
                    priority = HIGH_PRIORITY;
                    if (numParameters == 2) {
                        IsNullValue secondValue = frame.getArgument(invokeInstruction, cpg, 1, sigParser);
                        if (!secondValue.isDefinitelyNotNull()) {
                            pattern = "DMI_ARGUMENTS_WRONG_ORDER";
                            priority = NORMAL_PRIORITY;
                        }
                    }
                }
        
                BugInstance warning = new BugInstance(this, pattern, priority)
                .addClassAndMethod(classContext.getJavaClass(), method).addOptionalAnnotation(annotation)
                .addCalledMethod(cpg, invokeInstruction).addSourceLine(classContext, method, location);
        
                bugReporter.reportBug(warning);
            }
        }
         */

        BitSet nullArgSet = frame.getArgumentSet(invokeInstruction, cpg,
                // Only choose non-exception values.
                // Values null on an exception path might be due to infeasible control flow.
                value -> value.mightBeNull() && !value.isException() && !value.isReturnValue());
        BitSet definitelyNullArgSet = frame.getArgumentSet(invokeInstruction, cpg, IsNullValue::isDefinitelyNull);
        nullArgSet.and(definitelyNullArgSet);
        if (nullArgSet.isEmpty()) {
            return;
        }
        if (DEBUG_NULLARG) {
            System.out.println("Null arguments passed: " + nullArgSet);
            System.out.println("Frame is: " + frame);
            System.out.println("# arguments: " + frame.getNumArguments(invokeInstruction, cpg));
            if (!(invokeInstruction instanceof INVOKEDYNAMIC)) {
                XMethod xm = XFactory.createXMethod(invokeInstruction, cpg);
                System.out.print("Signature: " + xm.getSignature());
            }
        }

        if (unconditionalDerefParamDatabase != null) {
            checkUnconditionallyDereferencedParam(location, cpg, typeDataflow, invokeInstruction, nullArgSet,
                    definitelyNullArgSet);
        }

        if (DEBUG_NULLARG) {
            System.out.println("Checking nonnull params");
        }
        checkNonNullParam(location, cpg, typeDataflow, invokeInstruction, nullArgSet, definitelyNullArgSet);

    }

    private void examinePutfieldInstruction(Location location, PUTFIELD ins, ConstantPoolGen cpg)
            throws DataflowAnalysisException {

        IsNullValueFrame frame = invDataflow.getFactAtLocation(location);
        if (!frame.isValid()) {
            return;
        }
        IsNullValue tos = frame.getTopValue();
        if (tos.isDefinitelyNull()) {
            XField field = XFactory.createXField(ins, cpg);
            NullnessAnnotation annotation = AnalysisContext.currentAnalysisContext().getNullnessAnnotationDatabase()
                    .getResolvedAnnotation(field, false);
            if (annotation == NullnessAnnotation.NONNULL) {

                BugAnnotation variableAnnotation = null;
                try {
                    ValueNumberFrame vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
                    ValueNumber valueNumber = vnaFrame.getTopValue();
                    variableAnnotation = ValueNumberSourceInfo.findAnnotationFromValueNumber(method, location, valueNumber,
                            vnaFrame, "VALUE_OF");

                } catch (DataflowAnalysisException | CFGBuilderException e) {
                    AnalysisContext.logError("error", e);
                }

                BugInstance warning = new BugInstance(this, "NP_STORE_INTO_NONNULL_FIELD",
                        tos.isDefinitelyNull() ? HIGH_PRIORITY : NORMAL_PRIORITY)
                        .addClassAndMethod(classContext.getJavaClass(), method)
                        .addField(field)
                        .addOptionalAnnotation(variableAnnotation)
                        .addSourceLine(classContext, method, location);

                bugReporter.reportBug(warning);
            }
        }
    }

    private void examineReturnInstruction(Location location) throws DataflowAnalysisException, CFGBuilderException {
        if (DEBUG_NULLRETURN) {
            System.out.println("Checking null return at " + location);
        }

        IsNullValueDataflow isNullValueDataflow = classContext.getIsNullValueDataflow(method);
        IsNullValueFrame frame = isNullValueDataflow.getFactAtLocation(location);
        ValueNumberFrame vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
        if (!vnaFrame.isValid()) {
            return;
        }
        ValueNumber valueNumber = vnaFrame.getTopValue();
        if (!frame.isValid()) {
            return;
        }
        IsNullValue tos = frame.getTopValue();
        if (tos.isDefinitelyNull()) {
            BugAnnotation variable = ValueNumberSourceInfo.findAnnotationFromValueNumber(method, location, valueNumber, vnaFrame,
                    "VALUE_OF");

            String bugPattern = "NP_NONNULL_RETURN_VIOLATION";
            int priority = NORMAL_PRIORITY;
            if (tos.isDefinitelyNull() && !tos.isException()) {
                priority = HIGH_PRIORITY;
            }
            String methodName = method.getName();
            if ("clone".equals(methodName)) {
                bugPattern = "NP_CLONE_COULD_RETURN_NULL";
                priority = NORMAL_PRIORITY;
            } else if ("toString".equals(methodName)) {
                bugPattern = "NP_TOSTRING_COULD_RETURN_NULL";
                priority = NORMAL_PRIORITY;
            }
            BugInstance warning = new BugInstance(this, bugPattern, priority)
                    .addClassAndMethod(classContext.getJavaClass(), method).addOptionalAnnotation(variable);
            bugAccumulator.accumulateBug(warning, SourceLineAnnotation.fromVisitedInstruction(classContext, method, location));
        }
    }

    private boolean hasManyPreceedingNullTests(int pc) {
        int ifNullTests = 0;
        BitSet seen = new BitSet();
        try {
            for (Iterator<Location> i = classContext.getCFG(method).locationIterator(); i.hasNext();) {
                Location loc = i.next();
                int pc2 = loc.getHandle().getPosition();
                if (pc2 >= pc || pc2 < pc - 30) {
                    continue;
                }
                Instruction ins = loc.getHandle().getInstruction();
                if ((ins instanceof IFNONNULL || ins instanceof IFNULL || ins instanceof NullnessConversationInstruction)
                        && !seen.get(pc2)) {
                    ifNullTests++;
                    seen.set(pc2);
                }
            }
            return ifNullTests > 2;
        } catch (CFGBuilderException e) {
            return false;
        }
    }

    @StaticConstant
    public static final Set<String> catchTypesForNull = Set.of("java/lang/NullPointerException", Values.SLASHED_JAVA_LANG_RUNTIMEEXCEPTION,
            Values.SLASHED_JAVA_LANG_EXCEPTION);

    public static boolean catchesNull(ConstantPool constantPool, Code code, Location location) {
        int position = location.getHandle().getPosition();

        for (String t : catchTypesForNull) {
            int catchSize = Util.getSizeOfSurroundingTryBlock(constantPool, code, t, position);
            if (catchSize < Integer.MAX_VALUE) {
                return true;
            }
        }

        return false;
    }

    private boolean safeCallToPrimateParseMethod(XMethod calledMethod, Location location) {
        int position = location.getHandle().getPosition();

        if (Values.DOTTED_JAVA_LANG_INTEGER.equals(calledMethod.getClassName())) {

            ConstantPool constantPool = classContext.getJavaClass().getConstantPool();
            Code code = method.getCode();

            int catchSize;

            catchSize = Util.getSizeOfSurroundingTryBlock(constantPool, code, "java/lang/NumberFormatException", position);
            if (catchSize < Integer.MAX_VALUE) {
                return true;
            }
            catchSize = Util.getSizeOfSurroundingTryBlock(constantPool, code, "java/lang/IllegalArgumentException", position);
            if (catchSize < Integer.MAX_VALUE) {
                return true;
            }

            catchSize = Util.getSizeOfSurroundingTryBlock(constantPool, code, Values.SLASHED_JAVA_LANG_RUNTIMEEXCEPTION, position);
            if (catchSize < Integer.MAX_VALUE) {
                return true;
            }
            catchSize = Util.getSizeOfSurroundingTryBlock(constantPool, code, Values.SLASHED_JAVA_LANG_EXCEPTION, position);
            if (catchSize < Integer.MAX_VALUE) {
                return true;
            }
        }
        return false;
    }

    private void checkUnconditionallyDereferencedParam(Location location, ConstantPoolGen cpg, TypeDataflow typeDataflow,
            InvokeInstruction invokeInstruction, BitSet nullArgSet, BitSet definitelyNullArgSet)
            throws DataflowAnalysisException, ClassNotFoundException {

        if (inExplicitCatchNullBlock(location)) {
            return;
        }
        boolean caught = inIndirectCatchNullBlock(location);
        if (caught && skipIfInsideCatchNull()) {
            return;
        }
        if (invokeInstruction instanceof INVOKEDYNAMIC) {
            return;
        }
        // See what methods might be called here
        XMethod calledMethod = XFactory.createXMethod(invokeInstruction, cpg);
        // If a parameter is already marked as nonnull, don't complain about it here.
        nullArgSet = (BitSet) nullArgSet.clone();
        definitelyNullArgSet = (BitSet) definitelyNullArgSet.clone();
        ClassDescriptor nonnullClassDesc = DescriptorFactory.createClassDescriptor(Nonnull.class);
        TypeQualifierValue<?> nonnullTypeQualifierValue = TypeQualifierValue.getValue(nonnullClassDesc, null);
        for (int i = nullArgSet.nextSetBit(0); i >= 0; i = nullArgSet.nextSetBit(i + 1)) {
            TypeQualifierAnnotation tqa = TypeQualifierApplications.getEffectiveTypeQualifierAnnotation(calledMethod, i,
                    nonnullTypeQualifierValue);
            if (tqa != null && tqa.when == When.ALWAYS) {
                nullArgSet.clear(i);
                definitelyNullArgSet.clear(i);
            }

        }
        TypeFrame typeFrame = typeDataflow.getFactAtLocation(location);
        Set<JavaClassAndMethod> targetMethodSet = Hierarchy.resolveMethodCallTargets(invokeInstruction, typeFrame, cpg);
        if (DEBUG_NULLARG) {
            System.out.println("Possibly called methods: " + targetMethodSet);
        }

        // See if any call targets unconditionally dereference one of the null
        // arguments
        BitSet unconditionallyDereferencedNullArgSet = new BitSet();
        List<JavaClassAndMethod> dangerousCallTargetList = new LinkedList<>();
        List<JavaClassAndMethod> veryDangerousCallTargetList = new LinkedList<>();
        for (JavaClassAndMethod targetMethod : targetMethodSet) {
            if (DEBUG_NULLARG) {
                System.out.println("For target method " + targetMethod);
            }

            ParameterProperty property = unconditionalDerefParamDatabase.getProperty(targetMethod.toMethodDescriptor());
            if (property == null) {
                continue;
            }
            if (DEBUG_NULLARG) {
                System.out.println("\tUnconditionally dereferenced params: " + property);
            }

            BitSet targetUnconditionallyDereferencedNullArgSet = property.getMatchingParameters(nullArgSet);

            if (targetUnconditionallyDereferencedNullArgSet.isEmpty()) {
                continue;
            }

            dangerousCallTargetList.add(targetMethod);

            unconditionallyDereferencedNullArgSet.or(targetUnconditionallyDereferencedNullArgSet);

            if (!property.getMatchingParameters(definitelyNullArgSet).isEmpty()) {
                veryDangerousCallTargetList.add(targetMethod);
            }
        }

        if (dangerousCallTargetList.isEmpty()) {
            return;
        }

        WarningPropertySet<WarningProperty> propertySet = new WarningPropertySet<>();

        // See if there are any safe targets
        Set<JavaClassAndMethod> safeCallTargetSet = new HashSet<>(targetMethodSet);
        safeCallTargetSet.removeAll(dangerousCallTargetList);
        if (safeCallTargetSet.isEmpty()) {
            propertySet.addProperty(NullArgumentWarningProperty.ALL_DANGEROUS_TARGETS);
            if (dangerousCallTargetList.size() == 1) {
                propertySet.addProperty(NullArgumentWarningProperty.MONOMORPHIC_CALL_SITE);
            }
        }

        // Call to private method? In theory there should be only one possible target.
        boolean privateCall = safeCallTargetSet.isEmpty() && dangerousCallTargetList.size() == 1
                && dangerousCallTargetList.get(0).getMethod().isPrivate();

        String bugType;
        int priority;
        if (privateCall || invokeInstruction.getOpcode() == Const.INVOKESTATIC
                || invokeInstruction.getOpcode() == Const.INVOKESPECIAL) {
            bugType = "NP_NULL_PARAM_DEREF_NONVIRTUAL";
            priority = HIGH_PRIORITY;
        } else if (safeCallTargetSet.isEmpty()) {
            bugType = "NP_NULL_PARAM_DEREF_ALL_TARGETS_DANGEROUS";
            priority = NORMAL_PRIORITY;
        } else {
            return;
        }

        if (caught) {
            priority++;
        }
        if (dangerousCallTargetList.size() > veryDangerousCallTargetList.size()) {
            priority++;
        } else {
            propertySet.addProperty(NullArgumentWarningProperty.ACTUAL_PARAMETER_GUARANTEED_NULL);
        }
        XMethod calledFrom = XFactory.createXMethod(classContext.getJavaClass(), method);

        if (safeCallToPrimateParseMethod(calledMethod, location)) {
            return;
        }
        BugInstance warning = new BugInstance(this, bugType, priority)
                .addClassAndMethod(classContext.getJavaClass(), method)
                .addMethod(calledMethod).describe(MethodAnnotation.METHOD_CALLED)
                .addSourceLine(classContext, method, location);

        // boolean uncallable = false;
        if (!AnalysisContext.currentXFactory().isCalledDirectlyOrIndirectly(calledFrom) && calledFrom.isPrivate()) {

            propertySet.addProperty(GeneralWarningProperty.IN_UNCALLABLE_METHOD);
            // uncallable = true;
        }
        // Check which params might be null
        addParamAnnotations(location, definitelyNullArgSet, unconditionallyDereferencedNullArgSet, propertySet, warning);

        if ("NP_NULL_PARAM_DEREF_ALL_TARGETS_DANGEROUS".equals(bugType)) {
            // Add annotations for dangerous method call targets
            for (JavaClassAndMethod dangerousCallTarget : veryDangerousCallTargetList) {
                warning.addMethod(dangerousCallTarget).describe(MethodAnnotation.METHOD_DANGEROUS_TARGET_ACTUAL_GUARANTEED_NULL);
            }
            dangerousCallTargetList.removeAll(veryDangerousCallTargetList);
            if (DEBUG_NULLARG) {
                // Add annotations for dangerous method call targets
                for (JavaClassAndMethod dangerousCallTarget : dangerousCallTargetList) {
                    warning.addMethod(dangerousCallTarget).describe(MethodAnnotation.METHOD_DANGEROUS_TARGET);
                }

                // Add safe method call targets.
                // This is useful to see which other call targets the analysis considered.
                for (JavaClassAndMethod safeMethod : safeCallTargetSet) {
                    warning.addMethod(safeMethod).describe(MethodAnnotation.METHOD_SAFE_TARGET);
                }
            }
        }

        decorateWarning(location, propertySet, warning);
        bugReporter.reportBug(warning);
    }

    private void decorateWarning(Location location, WarningPropertySet<WarningProperty> propertySet, BugInstance warning) {
        if (FindBugsAnalysisFeatures.isRelaxedMode()) {
            WarningPropertyUtil.addPropertiesForDataMining(propertySet, classContext, method, location);
        }
        propertySet.decorateBugInstance(warning);
    }

    private void addParamAnnotations(Location location, BitSet definitelyNullArgSet, BitSet violatedParamSet,
            WarningPropertySet<? super NullArgumentWarningProperty> propertySet, BugInstance warning) {
        ValueNumberFrame vnaFrame = null;
        try {
            vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
        } catch (DataflowAnalysisException | CFGBuilderException e) {
            AnalysisContext.logError("error", e);
        }

        InvokeInstruction instruction = (InvokeInstruction) location.getHandle().getInstruction();
        SignatureParser sigParser = new SignatureParser(instruction.getSignature(classContext.getConstantPoolGen()));

        for (int i = violatedParamSet.nextSetBit(0); i >= 0; i = violatedParamSet.nextSetBit(i + 1)) {
            boolean definitelyNull = definitelyNullArgSet.get(i);

            if (definitelyNull) {
                propertySet.addProperty(NullArgumentWarningProperty.ARG_DEFINITELY_NULL);
            }
            ValueNumber valueNumber = null;
            if (vnaFrame != null) {
                try {
                    valueNumber = vnaFrame.getArgument(instruction, classContext.getConstantPoolGen(), i, sigParser);
                    BugAnnotation variableAnnotation = ValueNumberSourceInfo.findAnnotationFromValueNumber(method, location,
                            valueNumber, vnaFrame, "VALUE_OF");
                    warning.addOptionalAnnotation(variableAnnotation);
                } catch (DataflowAnalysisException e) {
                    AnalysisContext.logError("error", e);
                }
            }

            // Note: we report params as being indexed starting from 1, not 0
            warning.addParameterAnnotation(i, definitelyNull ? "INT_NULL_ARG" : "INT_MAYBE_NULL_ARG");

        }
    }

    /**
     * We have a method invocation in which a possibly or definitely null
     * parameter is passed. Check it against the library of nonnull annotations.
     *
     * @param location
     * @param cpg
     * @param typeDataflow
     * @param invokeInstruction
     * @param nullArgSet
     * @param definitelyNullArgSet
     */
    private void checkNonNullParam(Location location, ConstantPoolGen cpg, TypeDataflow typeDataflow,
            InvokeInstruction invokeInstruction, BitSet nullArgSet, BitSet definitelyNullArgSet) {

        if (inExplicitCatchNullBlock(location)) {
            return;
        }
        boolean caught = inIndirectCatchNullBlock(location);
        if (caught && skipIfInsideCatchNull()) {
            return;
        }
        if (invokeInstruction instanceof INVOKEDYNAMIC) {
            return;
        }
        XMethod m = XFactory.createXMethod(invokeInstruction, cpg);

        INullnessAnnotationDatabase db = AnalysisContext.currentAnalysisContext().getNullnessAnnotationDatabase();
        SignatureParser sigParser = new SignatureParser(invokeInstruction.getSignature(cpg));
        for (int i = nullArgSet.nextSetBit(0); i >= 0; i = nullArgSet.nextSetBit(i + 1)) {

            if (db.parameterMustBeNonNull(m, i)) {
                boolean definitelyNull = definitelyNullArgSet.get(i);
                if (DEBUG_NULLARG) {
                    System.out.println("Checking " + m);
                    System.out.println("QQQ2: " + i + " -- " + i + " is null");
                    System.out.println("QQQ nullArgSet: " + nullArgSet);
                    System.out.println("QQQ dnullArgSet: " + definitelyNullArgSet);
                }
                BugAnnotation variableAnnotation = null;
                try {
                    ValueNumberFrame vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
                    ValueNumber valueNumber = vnaFrame.getArgument(invokeInstruction, cpg, i, sigParser);
                    variableAnnotation = ValueNumberSourceInfo.findAnnotationFromValueNumber(method, location, valueNumber,
                            vnaFrame, "VALUE_OF");

                } catch (DataflowAnalysisException | CFGBuilderException e) {
                    AnalysisContext.logError("error", e);
                }

                int priority = definitelyNull ? HIGH_PRIORITY : NORMAL_PRIORITY;
                if (caught) {
                    priority++;
                }
                if (m.isPrivate() && priority == HIGH_PRIORITY) {
                    priority = NORMAL_PRIORITY;
                }
                String description = definitelyNull ? "INT_NULL_ARG" : "INT_MAYBE_NULL_ARG";
                WarningPropertySet<WarningProperty> propertySet = new WarningPropertySet<>();
                Set<Location> derefLocationSet = Collections.singleton(location);

                addPropertiesForDereferenceLocations(propertySet, derefLocationSet, false);

                boolean duplicated = isDuplicated(propertySet, location.getHandle().getPosition(), false);

                if (duplicated) {
                    return;
                }
                BugInstance warning = new BugInstance(this, "NP_NONNULL_PARAM_VIOLATION", priority)
                        .addClassAndMethod(classContext.getJavaClass(), method)
                        .addMethod(m).describe(MethodAnnotation.METHOD_CALLED)
                        .addParameterAnnotation(i, description)
                        .addOptionalAnnotation(variableAnnotation)
                        .addSourceLine(classContext, method, location);

                propertySet.decorateBugInstance(warning);
                bugReporter.reportBug(warning);
            }
        }

    }

    @Override
    public void report() {
    }

    private boolean skipIfInsideCatchNull() {
        return classContext.getJavaClass().getClassName().contains("Test") || method.getName().contains("test")
                || method.getName().contains("Test");
    }

    /**
     * @deprecated Use
     *             {@link #foundNullDeref(Location,ValueNumber,IsNullValue,ValueNumberFrame,boolean)}
     *             instead
     */
    @Deprecated
    @Override
    public void foundNullDeref(Location location, ValueNumber valueNumber, IsNullValue refValue, ValueNumberFrame vnaFrame) {
        foundNullDeref(location, valueNumber, refValue, vnaFrame, true);
    }

    @Override
    public void foundNullDeref(Location location, ValueNumber valueNumber, IsNullValue refValue, ValueNumberFrame vnaFrame,
            boolean isConsistent) {
        WarningPropertySet<WarningProperty> propertySet = new WarningPropertySet<>();
        if (valueNumber.hasFlag(ValueNumber.CONSTANT_CLASS_OBJECT)) {
            return;
        }

        boolean onExceptionPath = refValue.isException();
        if (onExceptionPath) {
            propertySet.addProperty(GeneralWarningProperty.ON_EXCEPTION_PATH);
        }
        int pc = location.getHandle().getPosition();
        BugAnnotation variable = ValueNumberSourceInfo.findAnnotationFromValueNumber(method, location, valueNumber, vnaFrame,
                "VALUE_OF");
        addPropertiesForDereferenceLocations(propertySet, Collections.singleton(location), isConsistent);
        Instruction ins = location.getHandle().getInstruction();
        if (ins instanceof InvokeInstruction && refValue.isDefinitelyNull()) {
            InvokeInstruction iins = (InvokeInstruction) ins;
            if ("close".equals(iins.getMethodName(classContext.getConstantPoolGen()))
                    && "()V".equals(iins.getSignature(classContext.getConstantPoolGen()))) {
                propertySet.addProperty(NullDerefProperty.CLOSING_NULL);
            }
        }
        boolean duplicated = isDuplicated(propertySet, pc, isConsistent);

        if (inExplicitCatchNullBlock(location)) {
            return;
        }
        boolean caught = inIndirectCatchNullBlock(location);
        if (caught && skipIfInsideCatchNull()) {
            return;
        }

        if (refValue.isDefinitelyNull()) {
            String type = "NP_ALWAYS_NULL";
            if (propertySet.containsProperty(NullDerefProperty.CLOSING_NULL)
                    && !propertySet.containsProperty(NullDerefProperty.DEREFS_ARE_CLONED)) {
                type = "NP_CLOSING_NULL";
            } else if (onExceptionPath) {
                type = "NP_ALWAYS_NULL_EXCEPTION";
            } else if (duplicated) {
                type = "NP_NULL_ON_SOME_PATH";
            }
            int priority = onExceptionPath ? NORMAL_PRIORITY : HIGH_PRIORITY;
            if (caught) {
                priority++;
            }
            reportNullDeref(propertySet, location, type, priority, variable);
        } else if (refValue.mightBeNull() && refValue.isParamValue()) {

            String type;
            int priority = NORMAL_PRIORITY;
            if (caught) {
                priority++;
            }

            if ("equals".equals(method.getName()) && "(Ljava/lang/Object;)Z".equals(method.getSignature())) {
                if (caught) {
                    return;
                }
                type = "NP_EQUALS_SHOULD_HANDLE_NULL_ARGUMENT";

            } else {
                type = "NP_ARGUMENT_MIGHT_BE_NULL";
            }

            if (DEBUG) {
                System.out.println("Reporting null on some path: value=" + refValue);
            }

            reportNullDeref(propertySet, location, type, priority, variable);
        }
    }

    public boolean isDuplicated(WarningPropertySet<WarningProperty> propertySet, int pc, boolean isConsistent) {
        boolean duplicated = false;
        if (!isConsistent) {
            if (propertySet.containsProperty(NullDerefProperty.DEREFS_ARE_CLONED)) {
                duplicated = true;
            } else {
                try {
                    CFG cfg = classContext.getCFG(method);
                    if (cfg.getLocationsContainingInstructionWithOffset(pc).size() > 1) {
                        propertySet.addProperty(NullDerefProperty.DEREFS_ARE_INLINED_FINALLY_BLOCKS);
                        duplicated = true;
                    }
                } catch (CFGBuilderException e) {
                    AnalysisContext.logError("Error while analyzing " + classContext.getFullyQualifiedMethodName(method), e);
                }
            }
        }
        return duplicated;
    }

    private void reportNullDeref(WarningPropertySet<WarningProperty> propertySet, Location location, String type, int priority,
            @CheckForNull BugAnnotation variable) {

        BugInstance bugInstance = new BugInstance(this, type, priority).addClassAndMethod(classContext.getJavaClass(), method);
        if (variable != null) {
            bugInstance.add(variable);
        } else {
            bugInstance.add(new LocalVariableAnnotation("?", -1, -1));
        }
        bugInstance.addSourceLine(classContext, method, location).describe("SOURCE_LINE_DEREF");

        if (FindBugsAnalysisFeatures.isRelaxedMode()) {
            WarningPropertyUtil.addPropertiesForDataMining(propertySet, classContext, method, location);
        }
        addPropertiesForDereferenceLocations(propertySet, Collections.singleton(location), false);

        propertySet.decorateBugInstance(bugInstance);

        bugReporter.reportBug(bugInstance);
    }

    public static boolean isThrower(BasicBlock target) {
        InstructionHandle ins = target.getFirstInstruction();
        int maxCount = 7;
        while (ins != null) {
            if (maxCount-- <= 0) {
                break;
            }
            Instruction i = ins.getInstruction();
            if (i instanceof ATHROW) {
                return true;
            }
            if (i instanceof InstructionTargeter || i instanceof ReturnInstruction) {
                return false;
            }
            ins = ins.getNext();
        }
        return false;
    }

    @Override
    public void foundRedundantNullCheck(Location location, RedundantBranch redundantBranch) {

        boolean isChecked = redundantBranch.firstValue.isChecked();
        boolean wouldHaveBeenAKaboom = redundantBranch.firstValue.wouldHaveBeenAKaboom();
        boolean isParameter = redundantBranch.firstValue.isParamValue();

        Location locationOfKaBoom = redundantBranch.firstValue.getLocationOfKaBoom();
        if (isParameter && !wouldHaveBeenAKaboom) {
            return;
        }
        boolean createdDeadCode = false;
        boolean infeasibleEdgeSimplyThrowsException = false;
        Edge infeasibleEdge = redundantBranch.infeasibleEdge;
        if (infeasibleEdge != null) {
            if (DEBUG) {
                System.out.println("Check if " + redundantBranch + " creates dead code");
            }
            BasicBlock target = infeasibleEdge.getTarget();

            if (DEBUG) {
                System.out.println("Target block is  "
                        + (target.isExceptionThrower() ? " exception thrower" : " not exception thrower"));
            }
            // If the block is empty, it probably doesn't matter that it was killed.
            // FIXME: really, we should crawl the immediately reachable blocks
            //  starting at the target block to see if any of them are dead and nonempty.
            boolean empty = !target.isExceptionThrower()
                    && (target.isEmpty() || isGoto(target.getFirstInstruction().getInstruction()));
            if (!empty) {
                try {
                    if (classContext.getCFG(method).getNumIncomingEdges(target) > 1) {
                        if (DEBUG) {
                            System.out.println("Target of infeasible edge has multiple incoming edges");
                        }
                        empty = true;
                    }
                } catch (CFGBuilderException e) {
                    assert true; // ignore it
                }
            }
            if (DEBUG) {
                System.out.println("Target block is  " + (empty ? "empty" : "not empty"));
            }

            if (!empty && isThrower(target)) {
                infeasibleEdgeSimplyThrowsException = true;
            }

            if (!empty && !previouslyDeadBlocks.get(target.getLabel())) {
                if (DEBUG) {
                    System.out.println("target was alive previously");
                }
                // Block was not dead before the null pointer analysis.
                // See if it is dead now by inspecting the null value frame.
                // If it's TOP, then the block became dead.
                IsNullValueFrame invFrame = invDataflow.getStartFact(target);
                createdDeadCode = invFrame.isTop();
                if (DEBUG) {
                    System.out.println("target is now " + (createdDeadCode ? "dead" : "alive"));
                }
            }
        }

        int priority;
        boolean valueIsNull = true;
        String warning;
        int pc = location.getHandle().getPosition();
        OpcodeStack stack = null;
        OpcodeStack.Item item1 = null;

        OpcodeStack.Item item2 = null;
        try {
            stack = OpcodeStackScanner.getStackAt(classContext.getJavaClass(), method, pc);

            item1 = stack.getStackItem(0);
        } catch (RuntimeException e) {
            if (SystemProperties.ASSERTIONS_ENABLED) {
                AnalysisContext.logError("Error getting stack at specific PC", e);
            }
            assert true;
        }
        if (redundantBranch.secondValue == null) {
            if (isGeneratedCodeInCatchBlock(method, pc)) {
                log.debug("skip RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE found in the generated code at {}", location);
                return;
            } else if (redundantBranch.firstValue.isDefinitelyNull()) {
                warning = "RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE";
                priority = NORMAL_PRIORITY;
            } else {
                warning = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE";
                valueIsNull = false;
                priority = isChecked ? HIGH_PRIORITY : NORMAL_PRIORITY;
            }
            if (infeasibleEdgeSimplyThrowsException) {
                priority++;
            }

        } else {
            if (stack != null) {
                item2 = stack.getStackItem(1);
            }
            boolean bothNull = redundantBranch.firstValue.isDefinitelyNull() && redundantBranch.secondValue.isDefinitelyNull();
            if (redundantBranch.secondValue.isChecked()) {
                isChecked = true;
            }
            if (redundantBranch.secondValue.wouldHaveBeenAKaboom()) {
                wouldHaveBeenAKaboom = true;
                locationOfKaBoom = redundantBranch.secondValue.getLocationOfKaBoom();
            }
            if (bothNull) {
                warning = "RCN_REDUNDANT_COMPARISON_TWO_NULL_VALUES";
                priority = NORMAL_PRIORITY;
            } else {
                warning = "RCN_REDUNDANT_COMPARISON_OF_NULL_AND_NONNULL_VALUE";
                priority = isChecked ? NORMAL_PRIORITY : LOW_PRIORITY;
            }

        }

        if (wouldHaveBeenAKaboom) {
            priority = HIGH_PRIORITY;
            warning = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE";
            if (locationOfKaBoom == null) {
                throw new NullPointerException("location of KaBoom is null");
            }
        }

        if (DEBUG) {
            System.out.println(createdDeadCode + " " + infeasibleEdgeSimplyThrowsException + " " + valueIsNull + " " + priority);
        }
        if (createdDeadCode) {
            if (!infeasibleEdgeSimplyThrowsException) {
                priority += 0;
            } else {
                // throw clause
                if (valueIsNull) {
                    priority += 0;
                } else {
                    priority += 1;
                }
            }
        } else {
            // didn't create any dead code
            priority += 1;
        }

        if (DEBUG) {
            System.out.println("RCN " + priority + " " + redundantBranch.firstValue + " =? " + redundantBranch.secondValue + " : "
                    + warning);

            if (isChecked) {
                System.out.println("isChecked");
            }
            if (wouldHaveBeenAKaboom) {
                System.out.println("wouldHaveBeenAKaboom");
            }
            if (createdDeadCode) {
                System.out.println("createdDeadCode");
            }
        }
        if (priority > LOW_PRIORITY) {
            return;
        }
        BugAnnotation variableAnnotation = null;
        try {
            // Get the value number
            ValueNumberFrame vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
            if (vnaFrame.isValid()) {
                Instruction ins = location.getHandle().getInstruction();

                ValueNumber valueNumber = vnaFrame.getInstance(ins, classContext.getConstantPoolGen());
                if (valueNumber.hasFlag(ValueNumber.CONSTANT_CLASS_OBJECT)) {
                    return;
                }
                variableAnnotation = ValueNumberSourceInfo.findAnnotationFromValueNumber(method, location, valueNumber, vnaFrame,
                        "VALUE_OF");
                if (variableAnnotation instanceof LocalVariableAnnotation) {
                    LocalVariableAnnotation local = (LocalVariableAnnotation) variableAnnotation;
                    if (!local.isNamed()) {
                        if ("RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE".equals(warning)) {
                            return;
                        }
                        priority++;
                    }
                }

            }
        } catch (DataflowAnalysisException | CFGBuilderException e) {
            // ignore
        }

        BugInstance bugInstance = new BugInstance(this, warning, priority).addClassAndMethod(classContext.getJavaClass(), method);
        LocalVariableAnnotation fallback = new LocalVariableAnnotation("?", -1, -1);
        boolean foundSource = bugInstance.tryAddingOptionalUniqueAnnotations(variableAnnotation,
                BugInstance.getFieldOrMethodValueSource(item1), BugInstance.getFieldOrMethodValueSource(item2));

        if (!foundSource) {
            if ("RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE".equals(warning)) {
                return;
            }
            bugInstance.setPriority(priority + 1);
            bugInstance.add(fallback);
        }
        if (wouldHaveBeenAKaboom) {
            bugInstance.addSourceLine(classContext, method, locationOfKaBoom);
        }

        if (FindBugsAnalysisFeatures.isRelaxedMode()) {
            WarningPropertySet<WarningProperty> propertySet = new WarningPropertySet<>();
            WarningPropertyUtil.addPropertiesForDataMining(propertySet, classContext, method, location);
            if (isChecked) {
                propertySet.addProperty(NullDerefProperty.CHECKED_VALUE);
            }
            if (wouldHaveBeenAKaboom) {
                propertySet.addProperty(NullDerefProperty.WOULD_HAVE_BEEN_A_KABOOM);
            }
            if (createdDeadCode) {
                propertySet.addProperty(NullDerefProperty.CREATED_DEAD_CODE);
            }

            propertySet.decorateBugInstance(bugInstance);
        }

        SourceLineAnnotation sourceLine = SourceLineAnnotation.fromVisitedInstruction(classContext, method, location);
        sourceLine.setDescription("SOURCE_REDUNDANT_NULL_CHECK");
        bugAccumulator.accumulateBug(bugInstance, sourceLine);
    }

    BugAnnotation getVariableAnnotation(Location location) {
        BugAnnotation variableAnnotation = null;
        try {
            // Get the value number
            ValueNumberFrame vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
            if (vnaFrame.isValid()) {
                Instruction ins = location.getHandle().getInstruction();

                ValueNumber valueNumber = vnaFrame.getInstance(ins, classContext.getConstantPoolGen());
                if (valueNumber.hasFlag(ValueNumber.CONSTANT_CLASS_OBJECT)) {
                    return null;
                }
                variableAnnotation = ValueNumberSourceInfo.findAnnotationFromValueNumber(method, location, valueNumber, vnaFrame,
                        "VALUE_OF");

            }
        } catch (DataflowAnalysisException | CFGBuilderException e) {
            // ignore
        }
        return variableAnnotation;

    }

    /**
     * Determine whether the given instruction is a goto.
     *
     * @param instruction
     *            the instruction
     * @return true if the instruction is a goto, false otherwise
     */
    private boolean isGoto(Instruction instruction) {
        return instruction.getOpcode() == Const.GOTO || instruction.getOpcode() == Const.GOTO_W;
    }

    int minPC(Collection<Location> locs) {
        int result = 1000000;
        for (Location l : locs) {
            if (result > l.getHandle().getPosition()) {
                result = l.getHandle().getPosition();
            }
        }
        return result;
    }

    int maxPC(Collection<Location> locs) {
        int result = -1000000;
        for (Location l : locs) {
            if (result < l.getHandle().getPosition()) {
                result = l.getHandle().getPosition();
            }
        }
        return result;
    }

    boolean callToAssertionMethod(Location loc) {

        InstructionHandle h = loc.getHandle();
        int firstPos = h.getPosition();

        LineNumberTable ln = method.getLineNumberTable();
        int firstLine = ln == null ? -1 : ln.getSourceLine(firstPos);

        while (h != null) {
            int pos = h.getPosition();

            if (ln == null) {
                if (pos > firstPos + 15) {
                    break;
                }
            } else {
                int line = ln.getSourceLine(pos);
                // For a call such as checkNotNull(get()) the 'pos' would be 'firstPos' + 3
                // Break the loop if we moved to a different source line AND moved the PC by at least 3
                // checkNotNull (or similar) might be formatted on a different line than its argument
                if (line != firstLine && pos > firstPos + 3) {
                    break;
                }
            }
            Instruction i = h.getInstruction();
            if (i instanceof InvokeInstruction) {
                InvokeInstruction ii = (InvokeInstruction) i;
                String name = ii.getMethodName(classContext.getConstantPoolGen());
                String className = ii.getClassName(classContext.getConstantPoolGen());

                if (("requireNonNull".equals(name) && "java.util.Objects".equals(className))
                        || name.startsWith("check") || name.startsWith("assert")) {
                    return true;
                }
            }
            h = h.getNext();
        }

        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @seeedu.umd.cs.findbugs.ba.npe.NullDerefAndRedundantComparisonCollector#
     * foundGuaranteedNullDeref(java.util.Set, java.util.Set,
     * edu.umd.cs.findbugs.ba.vna.ValueNumber, boolean)
     */
    @Override
    public void foundGuaranteedNullDeref(@Nonnull Set<Location> assignedNullLocationSet, @Nonnull Set<Location> derefLocationSet,
            SortedSet<Location> doomedLocations, ValueNumberDataflow vna, ValueNumber refValue,
            @CheckForNull BugAnnotation variableAnnotation, NullValueUnconditionalDeref deref, boolean npeIfStatementCovered) {
        if (refValue.hasFlag(ValueNumber.CONSTANT_CLASS_OBJECT)) {
            return;
        }

        if (DEBUG) {
            System.out.println("Found guaranteed null deref in " + method.getName());
            for (Location loc : doomedLocations) {
                System.out.println("Doomed at " + loc);
            }
        }

        String bugType;

        int priority = npeIfStatementCovered ? HIGH_PRIORITY : NORMAL_PRIORITY;

        if (deref.isMethodReturnValue()) {
            if (deref.isReadlineValue()) {
                bugType = "NP_DEREFERENCE_OF_READLINE_VALUE";
            } else if (deref.isAlwaysCheckForNullMethodReturn()
                    && deref.getDerefLocationSet().stream().allMatch(this::callToAssertionMethod)) {
                // The value was the return of a CheckForNull annotated-method and the code is deliberately asserting its nullness
                return;
            } else {
                bugType = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE";
            }
        } else if (derefLocationSet.size() > 1) {
            if (!deref.isAlwaysOnExceptionPath()) {
                bugType = "NP_GUARANTEED_DEREF";
            } else {
                bugType = "NP_GUARANTEED_DEREF_ON_EXCEPTION_PATH";
            }
        } else if (!deref.isAlwaysOnExceptionPath()) {
            bugType = "NP_NULL_ON_SOME_PATH";
        } else {
            bugType = "NP_NULL_ON_SOME_PATH_EXCEPTION";
        }

        boolean allCallToAssertionMethod = !doomedLocations.isEmpty();
        for (Location loc : doomedLocations) {
            if (!callToAssertionMethod(loc)) {
                allCallToAssertionMethod = false;
            }
        }
        if (allCallToAssertionMethod) {
            return;
        }

        // Add Locations in the set of locations at least one of which is guaranteed to be dereferenced

        SortedSet<Location> sourceLocations;
        if (doomedLocations.isEmpty() || doomedLocations.size() > 3 && doomedLocations.size() > assignedNullLocationSet.size()) {
            sourceLocations = new TreeSet<>(assignedNullLocationSet);
        } else {
            sourceLocations = doomedLocations;
        }

        if (doomedLocations.isEmpty() || derefLocationSet.isEmpty()) {
            return;
        }

        WarningPropertySet<WarningProperty> propertySet = new WarningPropertySet<>();

        addPropertiesForDereferenceLocations(propertySet, derefLocationSet, false);

        int minDereferencePC = minPC(derefLocationSet);
        int distance1 = minDereferencePC - maxPC(assignedNullLocationSet);
        int distance2 = minDereferencePC - maxPC(doomedLocations);
        int distance = Math.max(distance1, distance2);

        // Create BugInstance

        BitSet knownNull = new BitSet();

        SortedSet<SourceLineAnnotation> knownNullLocations = new TreeSet<>();
        for (Location loc : sourceLocations) {
            SourceLineAnnotation sourceLineAnnotation = SourceLineAnnotation.fromVisitedInstruction(classContext, method, loc);
            int startLine = sourceLineAnnotation.getStartLine();
            if (startLine == -1) {
                knownNullLocations.add(sourceLineAnnotation);
            } else if (!knownNull.get(startLine)) {
                knownNull.set(startLine);
                knownNullLocations.add(sourceLineAnnotation);
            }
        }

        FieldAnnotation storedField = null;
        MethodAnnotation invokedMethod = null;
        XMethod invokedXMethod = null;
        int parameterNumber = -1;
        if (derefLocationSet.size() == 1) {

            Location loc = derefLocationSet.iterator().next();

            PointerUsageRequiringNonNullValue pu = null;
            try {
                UsagesRequiringNonNullValues usages = classContext.getUsagesRequiringNonNullValues(method);
                pu = usages.get(loc, refValue, vnaDataflow);
            } catch (DataflowAnalysisException | CFGBuilderException e) {
                AnalysisContext.logError("Error getting UsagesRequiringNonNullValues for " + method, e);
            }

            if (pu == null) {
                assert true; // nothing to do
            } else if (deref.isReadlineValue()) {
                bugType = "NP_DEREFERENCE_OF_READLINE_VALUE";
                priority = NORMAL_PRIORITY;
            } else if (deref.isMethodReturnValue() && !deref.isReadlineValue()) {
                bugType = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE";
                priority = NORMAL_PRIORITY;
            } else if (pu.isReturnFromNonNullMethod()) {
                bugType = "NP_NONNULL_RETURN_VIOLATION";
                String methodName = method.getName();
                String methodSig = method.getSignature();
                if ("clone".equals(methodName) && "()Ljava/lang/Object;".equals(methodSig)) {
                    bugType = "NP_CLONE_COULD_RETURN_NULL";
                    priority = NORMAL_PRIORITY;
                } else if ("toString".equals(methodName) && "()Ljava/lang/String;".equals(methodSig)) {
                    bugType = "NP_TOSTRING_COULD_RETURN_NULL";
                    priority = NORMAL_PRIORITY;
                }

            } else {
                XField f = pu.getNonNullField();
                if (f != null) {
                    storedField = FieldAnnotation.fromXField(f);
                    bugType = "NP_STORE_INTO_NONNULL_FIELD";
                } else {
                    XMethodParameter mp = pu.getNonNullParameter();
                    if (mp != null) {
                        invokedXMethod = mp.getMethod();
                        for (Location derefLoc : derefLocationSet) {
                            if (safeCallToPrimateParseMethod(invokedXMethod, derefLoc)) {
                                return;
                            }
                        }
                        invokedMethod = MethodAnnotation.fromXMethod(mp.getMethod());
                        if (mp.getParameterNumber() == 0
                                && TypeQualifierNullnessAnnotationDatabase.assertsFirstParameterIsNonnull(invokedXMethod)) {
                            return;
                        }
                        parameterNumber = mp.getParameterNumber();
                        bugType = "NP_NULL_PARAM_DEREF";
                    }
                }
            }
        }

        boolean hasManyNullTests = true;
        for (SourceLineAnnotation sourceLineAnnotation : knownNullLocations) {
            if (!hasManyPreceedingNullTests(sourceLineAnnotation.getStartBytecode())) {
                hasManyNullTests = false;
            }
        }
        if (hasManyNullTests) {
            if ("NP_NULL_ON_SOME_PATH".equals(bugType) || "NP_GUARANTEED_DEREF".equals(bugType)) {
                bugType = "NP_NULL_ON_SOME_PATH_MIGHT_BE_INFEASIBLE";
            } else {
                priority++;
            }
        }

        BugInstance bugInstance = new BugInstance(this, bugType, priority).addClassAndMethod(classContext.getJavaClass(), method);
        if (invokedMethod != null) {
            assert invokedXMethod != null;
            XMethod i = invokedXMethod.resolveAccessMethodForMethod();
            if (i != invokedXMethod) {
                bugInstance.addMethod(i).describe(MethodAnnotation.METHOD_CALLED);
            } else {
                bugInstance.addMethod(invokedMethod).describe(MethodAnnotation.METHOD_CALLED)
                        .addParameterAnnotation(parameterNumber, "INT_MAYBE_NULL_ARG");
            }
        }
        if (storedField != null) {
            bugInstance.addField(storedField).describe("FIELD_STORED");
        }
        bugInstance.addOptionalAnnotation(variableAnnotation);
        if (variableAnnotation instanceof FieldAnnotation) {
            bugInstance.describe("FIELD_CONTAINS_VALUE");
        }

        addPropertiesForDereferenceLocations(propertySet, derefLocationSet, false);

        if (deref.isAlwaysOnExceptionPath()) {
            propertySet.addProperty(NullDerefProperty.ALWAYS_ON_EXCEPTION_PATH);
        }

        if (!assignedNullLocationSet.isEmpty() && distance > 100) {
            propertySet.addProperty(NullDerefProperty.LONG_RANGE_NULL_SOURCE);
        }

        propertySet.decorateBugInstance(bugInstance);

        if ("NP_DEREFERENCE_OF_READLINE_VALUE".equals(bugType)) {

            int source = -9999;
            if (knownNullLocations.size() == 1) {
                source = knownNullLocations.iterator().next().getEndBytecode();
            }
            for (Location loc : derefLocationSet) {
                int pos = loc.getHandle().getPosition();
                if (pos != source + 3) {
                    // another detector
                    bugAccumulator.accumulateBug(bugInstance, SourceLineAnnotation.fromVisitedInstruction(classContext, method, loc));
                }
            }

        } else {
            for (Location loc : derefLocationSet) {
                bugInstance.addSourceLine(classContext, method, loc).describe(getDescription(loc, refValue));
            }

            if (sourceLocations == doomedLocations && assignedNullLocationSet.size() == 1) {
                Location assignedNull = assignedNullLocationSet.iterator().next();
                SourceLineAnnotation sourceLineAnnotation = SourceLineAnnotation.fromVisitedInstruction(classContext, method, assignedNull);
                int startLine = sourceLineAnnotation.getStartLine();
                if (startLine > 0 && !knownNull.get(startLine)) {
                    bugInstance.add(sourceLineAnnotation).describe("SOURCE_LINE_NULL_VALUE");
                }

            }
            for (SourceLineAnnotation sourceLineAnnotation : knownNullLocations) {
                bugInstance.add(sourceLineAnnotation).describe("SOURCE_LINE_KNOWN_NULL");
            }

            // Report it
            bugReporter.reportBug(bugInstance);
        }
    }

    private void addPropertiesForDereferenceLocations(WarningPropertySet<WarningProperty> propertySet,
            Collection<Location> derefLocationSet, boolean isConsistent) {
        boolean derefOutsideCatchBlock = false;

        boolean derefOutsideCatchNullBlock = false;
        boolean allDerefsAtDoomedLocations = true;

        for (Location loc : derefLocationSet) {
            if (!inExplicitCatchNullBlock(loc)) {
                derefOutsideCatchNullBlock = true;
                if (!inIndirectCatchNullBlock(loc)) {
                    derefOutsideCatchBlock = true;
                }
            }

            if (!isDoomed(loc)) {
                allDerefsAtDoomedLocations = false;
            }
        }

        if (!derefOutsideCatchNullBlock) {
            propertySet.addProperty(GeneralWarningProperty.FALSE_POSITIVE);
            return;
        }

        if (allDerefsAtDoomedLocations) {
            // Add a WarningProperty
            propertySet.addProperty(DoomedCodeWarningProperty.DOOMED_CODE);
        }
        boolean uniqueDereferenceLocations = uniqueLocations(derefLocationSet);

        if (!derefOutsideCatchBlock) {
            if (!uniqueDereferenceLocations || skipIfInsideCatchNull()) {
                propertySet.addProperty(GeneralWarningProperty.FALSE_POSITIVE);
            } else {
                propertySet.addProperty(NullDerefProperty.DEREFS_IN_CATCH_BLOCKS);
            }
        }
        if (!isConsistent && !uniqueDereferenceLocations) {
            // Add a WarningProperty
            propertySet.addProperty(NullDerefProperty.DEREFS_ARE_CLONED);
        }

        addPropertiesForMethodContainingWarning(propertySet);
    }

    private boolean uniqueLocations(Collection<Location> derefLocationSet) {
        boolean uniqueDereferenceLocations = false;
        CodeException[] exceptionTable = method.getCode().getExceptionTable();
        if (exceptionTable == null) {
            return true;
        }
        checkForCatchAll: {
            for (CodeException e : exceptionTable) {
                if (e.getCatchType() == 0) {
                    break checkForCatchAll;
                }
            }
            return true;
        }

        LineNumberTable table = method.getLineNumberTable();
        if (table == null) {
            uniqueDereferenceLocations = true;
        } else {
            BitSet linesMentionedMultipleTimes = classContext.linesMentionedMultipleTimes(method);
            for (Location loc : derefLocationSet) {
                int lineNumber = table.getSourceLine(loc.getHandle().getPosition());
                if (lineNumber > 0 && !linesMentionedMultipleTimes.get(lineNumber)) {
                    uniqueDereferenceLocations = true;
                }
            }
        }
        return uniqueDereferenceLocations;
    }

    private void addPropertiesForMethodContainingWarning(WarningPropertySet<WarningProperty> propertySet) {
        XMethod xMethod = XFactory.createXMethod(classContext.getJavaClass(), method);

        boolean uncallable = !AnalysisContext.currentXFactory().isCalledDirectlyOrIndirectly(xMethod) && xMethod.isPrivate();

        if (uncallable) {
            propertySet.addProperty(GeneralWarningProperty.IN_UNCALLABLE_METHOD);
        }
    }

    private boolean isDoomed(Location loc) {
        if (!MARK_DOOMED) {
            return false;
        }

        ReturnPathTypeDataflow rptDataflow;
        try {
            rptDataflow = classContext.getReturnPathTypeDataflow(method);

            ReturnPathType rpt = rptDataflow.getFactAtLocation(loc);

            return !rpt.canReturnNormally();
        } catch (CheckedAnalysisException e) {
            AnalysisContext.logError("Error getting return path type", e);
            return false;
        }
    }

    private String getDescription(Location loc, ValueNumber refValue) {
        PointerUsageRequiringNonNullValue pu;
        try {
            UsagesRequiringNonNullValues usages = classContext.getUsagesRequiringNonNullValues(method);
            pu = usages.get(loc, refValue, vnaDataflow);
            if (pu == null) {
                return "SOURCE_LINE_DEREF";
            }
            return pu.getDescription();
        } catch (DataflowAnalysisException | CFGBuilderException e) {
            AnalysisContext.logError("Error getting UsagesRequiringNonNullValues for " + method, e);
            return "SOURCE_LINE_DEREF";
        }

    }

    private boolean inExplicitCatchNullBlock(Location loc) {
        int pc = loc.getHandle().getPosition();
        int catchSize = Util.getSizeOfSurroundingTryBlock(classContext.getJavaClass().getConstantPool(), method.getCode(),
                "java/lang/NullPointerException", pc);
        return catchSize < Integer.MAX_VALUE;
    }

    private boolean inIndirectCatchNullBlock(Location loc) {
        int pc = loc.getHandle().getPosition();
        int catchSize = Util.getSizeOfSurroundingTryBlock(classContext.getJavaClass().getConstantPool(), method.getCode(),
                Values.SLASHED_JAVA_LANG_EXCEPTION, pc);
        if (catchSize < 5) {
            return true;
        }
        catchSize = Util.getSizeOfSurroundingTryBlock(classContext.getJavaClass().getConstantPool(), method.getCode(),
                Values.SLASHED_JAVA_LANG_RUNTIMEEXCEPTION, pc);
        if (catchSize < 5) {
            return true;
        }
        catchSize = Util.getSizeOfSurroundingTryBlock(classContext.getJavaClass().getConstantPool(), method.getCode(),
                Values.SLASHED_JAVA_LANG_THROWABLE, pc);
        return catchSize < 5;
    }

    /**
     * Java 11+ compiler generates redundant null checks for try-with-resources.
     * This method detects the {@code ifnull} bytecode generated by javac,
     * to help the detector to avoid to report needless {@code RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE} bug.
     *
     * @param method the method
     * @param pc the program counter
     * @return true if the pc specifies redundant null check generated by javac
     * @see
     * <a href="https://github.com/spotbugs/spotbugs/issues/259">false positive RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE on try-with-resources</a>
     * @see
     * <a href="https://github.com/spotbugs/spotbugs/issues/600">false positive RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE on try-with-resources</a>
     */
    public static boolean isGeneratedCodeInCatchBlock(@NonNull Method method, int pc) {
        ConstantPool cp = method.getConstantPool();
        Code code = method.getCode();
        if (code == null) {
            return false;
        }
        CodeException[] table = code.getExceptionTable();

        // TODO: This instantiation could be high cost computation
        InstructionList list = new InstructionList(code.getCode());

        List<CodeException> throwableList = Arrays.stream(table)
                .filter(codeException -> {
                    int catchType = codeException.getCatchType();
                    if (catchType == 0) {
                        // '0' means it catches any exceptions
                        return true;
                    }
                    String exceptionName = cp.getConstantString(catchType, Const.CONSTANT_Class);
                    return Values.SLASHED_JAVA_LANG_THROWABLE.equals(exceptionName);
                })
                .collect(Collectors.toList());

        LineNumberTable lineNumberTable = code.getLineNumberTable();
        if (lineNumberTable != null) {
            // this line also marks the end of the try catch block
            int line = lineNumberTable.getSourceLine(pc);
            if (line > 0) {
                return isGeneratedCodeInCatchBlockViaLineNumber(cp, lineNumberTable, line, list, throwableList);
            }
        }

        // assume that programmers rarely catch java.lang.Throwable instance explicitly
        return throwableList
                .stream()
                .anyMatch(codeException -> {
                    InstructionHandle handle = list.findHandle(codeException.getEndPC());
                    int insnLength = handle.getInstruction().getLength();
                    return codeException.getEndPC() + insnLength == pc;
                });
    }

    /**
     * Java 11+ compiler generates redundant null checks for try-with-resources.
     * This method tries to distinguish such code from manually written code by analysing null numbers.
     * @param cp the constant pool
     * @param lineNumberTable the table with the line numbers
     * @param line the line which belongs to the program counter
     * @param instructions the list of instructions
     * @param throwables the list of Throwables in this method
     * @return true if the pc specifies redundant null check generated by javac
     */
    private static boolean isGeneratedCodeInCatchBlockViaLineNumber(@NonNull ConstantPool cp, @NonNull LineNumberTable lineNumberTable,
            int line, @NonNull InstructionList instructions, @NonNull List<CodeException> throwables) {
        // The compiler generated code can also be written by the regular program. The only
        // difference is that the line numbers for a lot of instructions are the same.
        // This is what the code below relies on. Line numbers are optional, so it might
        // not work in call cases.
        //
        // The following operations must have the same line numbers as the start or end of the original try catch block.
        // - the reported code position (which is the null check)
        // - at least one assignment
        // - at least one call of addSuppressed
        // - at least one throws statement
        //
        // The two calls to the close method need to have the same line number as the end of a throwables catch block.
        // There must be also another catch of Throwable, but the line number does not need to match.
        //
        // To understand the code, please have a look at the test Issue600Test.
        // TryWithResources* are the try-with-resources examples, ExplicitNullCheck* is TryWithResources* compiled and then decompiled.

        ConstantPoolGen cpg = new ConstantPoolGen(cp);

        // The two generated catch blocks might show different starting line numbers. Both are needed.
        Set<Integer> relevantLineNumbers = throwables.stream()
                .map(x -> lineNumberTable.getSourceLine(x.getStartPC()))
                .collect(Collectors.toSet());
        relevantLineNumbers.add(line);

        boolean assignmentPresent = false;
        boolean addSuppressedPresent = false;
        boolean throwsPresent = false;
        int closeCounter = 0;
        for (InstructionHandle handle : instructions.getInstructionHandles()) {
            int currentLine = lineNumberTable.getSourceLine(handle.getPosition());

            switch (handle.getInstruction().getOpcode()) {
            case Const.ASTORE:
            case Const.ASTORE_0:
            case Const.ASTORE_1:
            case Const.ASTORE_2:
            case Const.ASTORE_3:
                if (relevantLineNumbers.contains(currentLine)) {
                    assignmentPresent = true;
                }
                break;
            case Const.INVOKEVIRTUAL:
                if (handle.getInstruction() instanceof INVOKEVIRTUAL) {
                    String methodName = ((INVOKEVIRTUAL) handle.getInstruction()).getMethodName(cpg);
                    if ("close".equals(methodName)) {
                        // the close methods get the line number of the end of the try block assigned
                        if (throwables.stream().anyMatch(x -> lineNumberTable.getSourceLine(x.getEndPC()) == currentLine)) {
                            closeCounter++;
                        }
                    } else if ("addSuppressed".equals(methodName) && relevantLineNumbers.contains(currentLine)) {
                        addSuppressedPresent = true;
                    }
                }
                break;
            case Const.INVOKEINTERFACE:
                if (handle.getInstruction() instanceof INVOKEINTERFACE) {
                    String methodName = ((INVOKEINTERFACE) handle.getInstruction()).getMethodName(cpg);
                    if ("close".equals(methodName)) {
                        // the close methods get the line number of the end of the try block assigned
                        if (throwables.stream().anyMatch(x -> lineNumberTable.getSourceLine(x.getEndPC()) == currentLine)) {
                            closeCounter++;
                        }
                    } else if ("addSuppressed".equals(methodName) && relevantLineNumbers.contains(currentLine)) {
                        addSuppressedPresent = true;
                    }
                }
                break;
            case Const.ATHROW:
                if (relevantLineNumbers.contains(currentLine) && handle.getInstruction() instanceof ATHROW) {
                    Class<?>[] exceptions = ((ATHROW) handle.getInstruction()).getExceptions();
                    if (exceptions.length == 1 && Throwable.class.equals(exceptions[0])) {
                        // even if try-with-resources catches exceptions, the compiler generates a nested try-catch with Throwable.
                        throwsPresent = true;
                    }
                }
                break;
            default:
                break;
            }
        }
        boolean matchingCatches = false;
        if (throwables.size() >= 2) {
            // make sure that the reported line matches the start or end line of the generated try catch blocks
            matchingCatches = throwables.stream().anyMatch(
                    x -> lineNumberTable.getSourceLine(x.getStartPC()) == line) || throwables.stream().anyMatch(
                            x -> lineNumberTable.getSourceLine(x.getEndPC()) == line);
        }
        return matchingCatches && assignmentPresent && addSuppressedPresent && throwsPresent && closeCounter >= 2;
    }
}
