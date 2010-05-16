/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.ipa.slicer;

import java.util.Collection;
import java.util.Collections;

import com.ibm.wala.dataflow.IFDS.BackwardsSupergraph;
import com.ibm.wala.dataflow.IFDS.IMergeFunction;
import com.ibm.wala.dataflow.IFDS.IPartiallyBalancedFlowFunctions;
import com.ibm.wala.dataflow.IFDS.ISupergraph;
import com.ibm.wala.dataflow.IFDS.PartiallyBalancedTabulationProblem;
import com.ibm.wala.dataflow.IFDS.PartiallyBalancedTabulationSolver;
import com.ibm.wala.dataflow.IFDS.PathEdge;
import com.ibm.wala.dataflow.IFDS.TabulationDomain;
import com.ibm.wala.dataflow.IFDS.TabulationResult;
import com.ibm.wala.dataflow.IFDS.UnorderedDomain;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.modref.ModRef;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.HashSetFactory;

/**
 * A demand-driven context-sensitive slicer.
 * 
 * This computes a context-sensitive slice, building an SDG and finding realizable paths to a statement using tabulation.
 * 
 * This implementation uses a preliminary pointer analysis to compute data dependence between heap locations in the SDG.
 */
public class Slicer {

  public final static boolean DEBUG = false;

  public final static boolean VERBOSE = false;

  /**
   * options to control data dependence edges in the SDG
   */
  public static enum DataDependenceOptions {
    FULL("full", false, false, false, false), NO_BASE_PTRS("no_base_ptrs", true, false, false, false), NO_BASE_NO_HEAP(
        "no_base_no_heap", true, true, false, false), NO_BASE_NO_EXCEPTIONS("no_base_no_exceptions", true, false, false, true), NO_BASE_NO_HEAP_NO_EXCEPTIONS(
        "no_base_no_heap_no_exceptions", true, true, false, true), NO_HEAP("no_heap", false, true, false, false), NO_HEAP_NO_EXCEPTIONS(
        "no_heap_no_exceptions", false, true, false, true), NO_EXCEPTIONS("no_exceptions", false, false, false, true), NONE("none",
        true, true, true, true), REFLECTION("no_base_no_heap_no_cast", true, true, true, true);

    private final String name;

    /**
     * Ignore data dependence edges representing base pointers? e.g for a statement y = x.f, ignore the data dependence edges for x
     */
    private final boolean ignoreBasePtrs;

    /**
     * Ignore all data dependence edges to or from the heap?
     */
    private final boolean ignoreHeap;

    /**
     * Ignore outgoing data dependence edges from a cast statements? [This is a special case option used for reflection processing]
     */
    private final boolean terminateAtCast;

    /**
     * Ignore data dependence manifesting throw exception objects?
     */
    private final boolean ignoreExceptions;

    DataDependenceOptions(String name, boolean ignoreBasePtrs, boolean ignoreHeap, boolean terminateAtCast, boolean ignoreExceptions) {
      this.name = name;
      this.ignoreBasePtrs = ignoreBasePtrs;
      this.ignoreHeap = ignoreHeap;
      this.terminateAtCast = terminateAtCast;
      this.ignoreExceptions = ignoreExceptions;
    }

    public final boolean isIgnoreBasePtrs() {
      return ignoreBasePtrs;
    }

    public final boolean isIgnoreHeap() {
      return ignoreHeap;
    }

    public final boolean isIgnoreExceptions() {
      return ignoreExceptions;
    }

    /**
     * Should data dependence chains terminate at casts? This is used for reflection processing ... we only track flow into casts
     * ... but not out.
     */
    public final boolean isTerminateAtCast() {
      return terminateAtCast;
    }

    public final String getName() {
      return name;
    }
  }

  /**
   * options to control control dependence edges in the sdg
   */
  public static enum ControlDependenceOptions {
    FULL("full"), NONE("none"), NO_EXCEPTIONAL_EDGES("no_exceptional_edges");

    private final String name;

    ControlDependenceOptions(String name) {
      this.name = name;
    }

    public final String getName() {
      return name;
    }
  }

  /**
   * @param s a statement of interest
   * @return the backward slice of s.
   * @throws CancelException
   */
  public static Collection<Statement> computeBackwardSlice(Statement s, CallGraph cg, PointerAnalysis pa,
      DataDependenceOptions dOptions, ControlDependenceOptions cOptions) throws IllegalArgumentException, CancelException {
    return computeSlice(new SDG(cg, pa, ModRef.make(), dOptions, cOptions), Collections.singleton(s), true);
  }

  /**
   * @param s a statement of interest
   * @return the forward slice of s.
   * @throws CancelException
   */
  public static Collection<Statement> computeForwardSlice(Statement s, CallGraph cg, PointerAnalysis pa,
      DataDependenceOptions dOptions, ControlDependenceOptions cOptions) throws IllegalArgumentException, CancelException {
    return computeSlice(new SDG(cg, pa, ModRef.make(), dOptions, cOptions), Collections.singleton(s), false);
  }

  /**
   * Use the passed-in SDG
   * 
   * @throws CancelException
   */
  public static Collection<Statement> computeBackwardSlice(SDG sdg, Statement s) throws IllegalArgumentException, CancelException {
    return computeSlice(sdg, Collections.singleton(s), true);
  }

  /**
   * Use the passed-in SDG
   * 
   * @throws CancelException
   */
  public static Collection<Statement> computeForwardSlice(SDG sdg, Statement s) throws IllegalArgumentException, CancelException {
    return computeSlice(sdg, Collections.singleton(s), false);
  }

  /**
   * Use the passed-in SDG
   * 
   * @throws CancelException
   */
  public static Collection<Statement> computeBackwardSlice(SDG sdg, Collection<Statement> ss) throws IllegalArgumentException,
      CancelException {
    return computeSlice(sdg, ss, true);
  }

  /**
   * @param ss a collection of statements of interest
   * @throws CancelException
   */
  protected static Collection<Statement> computeSlice(SDG sdg, Collection<Statement> ss, boolean backward) throws CancelException {
    if (sdg == null) {
      throw new IllegalArgumentException("sdg cannot be null");
    }
    return new Slicer().slice(sdg, ss, backward);
  }

  /**
   * Main driver logic.
   * 
   * @param sdg governing system dependence graph
   * @param roots set of roots to slice from
   * @param backward do a backwards slice?
   * @return the {@link Statement}s found by the slicer
   * @throws CancelException
   */
  public Collection<Statement> slice(SDG sdg, Collection<Statement> roots, boolean backward) throws CancelException {
    if (sdg == null) {
      throw new IllegalArgumentException("sdg cannot be null");
    }

    SliceProblem p = makeSliceProblem(roots, sdg, backward);

    PartiallyBalancedTabulationSolver<Statement, PDG, Object> solver = PartiallyBalancedTabulationSolver
        .createPartiallyBalancedTabulationSolver(p, null);
    TabulationResult<Statement, PDG, Object> tr = solver.solve();

    Collection<Statement> slice = tr.getSupergraphNodesReached();

    if (VERBOSE) {
      System.err.println("Slicer done.");
    }

    return slice;
  }

  /**
   * Return an object which encapsulates the tabulation logic for the slice problem. Subclasses can override this method to
   * implement special semantics.
   */
  protected SliceProblem makeSliceProblem(Collection<Statement> roots, ISDG sdgView, boolean backward) {
    return new SliceProblem(roots, sdgView, backward);
  }

  /**
   * @param s a statement of interest
   * @return the backward slice of s.
   * @throws CancelException
   */
  public static Collection<Statement> computeBackwardSlice(Statement s, CallGraph cg, PointerAnalysis pointerAnalysis)
      throws IllegalArgumentException, CancelException {
    return computeBackwardSlice(s, cg, pointerAnalysis, DataDependenceOptions.FULL, ControlDependenceOptions.FULL);
  }

  /**
   * Tabulation problem representing slicing
   * 
   */
  public static class SliceProblem implements PartiallyBalancedTabulationProblem<Statement, PDG, Object> {

    private final Collection<Statement> roots;

    private final ISupergraph<Statement, PDG> supergraph;

    private final SliceFunctions f;

    private final boolean backward;

    public SliceProblem(Collection<Statement> roots, ISDG sdg, boolean backward) {
      this.roots = roots;
      this.backward = backward;
      SDGSupergraph forwards = new SDGSupergraph(sdg, backward);
      this.supergraph = backward ? BackwardsSupergraph.make(forwards) : forwards;
      f = new SliceFunctions();
    }

    /*
     * @see com.ibm.wala.dataflow.IFDS.TabulationProblem#getDomain()
     */
    public TabulationDomain<Object, Statement> getDomain() {
      // a dummy
      return new UnorderedDomain<Object, Statement>();
    }

    /*
     * @see com.ibm.wala.dataflow.IFDS.TabulationProblem#getFunctionMap()
     */
    public IPartiallyBalancedFlowFunctions<Statement> getFunctionMap() {
      return f;
    }

    /*
     * @see com.ibm.wala.dataflow.IFDS.TabulationProblem#getMergeFunction()
     */
    public IMergeFunction getMergeFunction() {
      return null;
    }

    /*
     * @see com.ibm.wala.dataflow.IFDS.TabulationProblem#getSupergraph()
     */
    public ISupergraph<Statement, PDG> getSupergraph() {
      return supergraph;
    }

    public Collection<PathEdge<Statement>> initialSeeds() {
      if (backward) {
        Collection<PathEdge<Statement>> result = HashSetFactory.make();
        for (Statement st : roots) {
          PathEdge<Statement> seed = PathEdge.createPathEdge(new MethodExitStatement(st.getNode()), 0, st, 0);
          result.add(seed);
        }
        return result;
      } else {
        Collection<PathEdge<Statement>> result = HashSetFactory.make();
        for (Statement st : roots) {
          PathEdge<Statement> seed = PathEdge.createPathEdge(new MethodEntryStatement(st.getNode()), 0, st, 0);
          result.add(seed);
        }
        return result;
      }
    }

    public Statement getFakeEntry(Statement node) {
      return backward ? new MethodExitStatement(node.getNode()) : new MethodEntryStatement(node.getNode());
    }

  }

}
