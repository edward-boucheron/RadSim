/* 
 * Copyright 2016, Lawrence Livermore National Security, LLC.
 * All rights reserved
 * 
 * Terms and conditions are given in "Notice" file.
 */
package gov.llnl.math.graph;

//#ifndef RNAK_GRAPHS_VITERBI_PROBABILITY_GRAPH_H_
import gov.llnl.math.graph.primitives.Connection;
import gov.llnl.math.graph.primitives.NodeEdgeList;
import gov.llnl.utility.UUIDUtilities;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.LinkedList;

/**
 * This algorithm processes a Probability Graph with a query to find the most
 * probable state. It also computes the variable that would result in the
 * largest change.
 * <p>
 * The algorithm is known as the Viterbi algorithm or Max-Product. Algorithm
 * uses message passing like Belief propagation with messages based on max
 * product.
 * <p>
 * Computes:
 * <ul>
 * <li> the largest probability given the assumptions
 * <li>the state with that probability
 * <li>the variable which if assumed true would make the largest change
 * </ul>
 *
 * @author nelson85
 */
public class ViterbiProbabilityGraph implements Serializable
{
  private static final long serialVersionUID = UUIDUtilities.createLong("ViterbiProbabilityGraph");
  /**
   * Internal structure held in workspace used to store the messages. Each datum
   * is associated with a node in the ProbabilityGraph.
   */
  static final public class Datum implements Serializable
  {
    // Members
    // Association with graph
    ProbabilityGraphNode node;

    // Queue (doubly linked so we can progate the reverse messages)
    Datum next;
    Datum previous;

    // Processing variables (to compute the order of visitation)
    // Variables used by assignOrder (only)
    int unvisitedNeighbors; // how many unvisited neighbors
    Datum messageTo; // who it is sent to

    // Messaging 
    double[] messageForward = new double[2]; // contents of message
    double[] messageReverse = new double[2]; // contents of message (reverse)
    int[] stateTracking = new int[2]; // tracking the best state for the neighbor to backtrace
    int best; // 0 or 1
    double ratio; // -1 if fixed variable, otherwise P(F)/P(T)

    private double total = -1;

    // Caching of query
    boolean freeVariable = false;

    // Methods
    /**
     * Creates a datum to be associated with a graph node.
     */
    public Datum()
    {
      clear();
    }

    /**
     * Initializes the datum and disposes of all resources associated with a
     * previous evaluation.
     */
    public final void clear()
    {
      unvisitedNeighbors = 0;
      next = null;
      previous = null;
      node = null;

      // Clear the messages (not vital as they should all be cleared as they are used)
      messageTo = null;
      messageForward[0] = 0;
      messageForward[1] = 0;
      messageReverse[0] = 1;
      messageReverse[1] = 1;
      stateTracking[0] = -1;
      stateTracking[1] = -1;
      best = -1;
      ratio = -1;
    }
  };

  /**
   * Workspace holds all temporary variables used while executing the Viterbi
   * algorithm. The Workspace is usually autogenerated when calling the
   * {@link ViterbiProbabilityGraph#execute(gov.llnl.math.graph.ViterbiProbabilityGraph.Output, gov.llnl.math.graph.ProbabilityGraph, gov.llnl.math.graph.GraphQuery) execute}
   * method, but can be created manually when needed to probe the internal
   * state.
   */
  public static class Workspace
  {
    private Datum[] data = null;
    private int querySize = 0;
    private Datum first;
    private Datum last;

    public Workspace()
    {
      first = null;
      last = null;
    }

    public void dispose()
    {
      data = null;
      first = null;
      last = null;
    }

    public void initialize(ProbabilityGraph pg)
    {
      LinkedList<ProbabilityGraphNode> nodes = pg.nodes();
      int num_nodes = nodes.size();
      data = new Datum[num_nodes];
      for (int i = 0; i < num_nodes; ++i)
      {
        data[i] = new Datum();
      }

      first = null;
      last = null;

      // Associate the data with the graph
      int i = 0;
      for (ProbabilityGraphNode node : nodes)
      {
        Datum datum = this.data[i];
        datum.clear();
        datum.node = node;
        datum.unvisitedNeighbors = node.getNumEdges();
        i++;
      }
    }

    public void enqueue(Datum datum)
    {
      // Do not add to the queue twice
      if (datum.next != null || datum.previous != null)
        return;

      // reset the next to be empty
      datum.next = null;
      datum.previous = last;

      if (last != null)
        last.next = datum;
      last = datum;

      if (first == null)
        first = datum;
    }

    //  public Datum dequeue();
    //public boolean empty();
    // Helpers
    public void composeMessageForwardVariableNode(Datum datum, GraphQuery query)
    {
      ProbabilityGraphNode node = datum.node;
      NodeEdgeList<ProbabilityGraphNode, ProbabilityGraphEdge> edges = node.getEdges();
      datum.freeVariable = false;

      // What message to pass will depend on the state of the query
      double[] factors = node.getVariableFactors(query);
      datum.messageForward[0] = factors[0];
      datum.messageForward[1] = factors[1];
      int id = node.getQueryId();
      if (query.isPartial(id))
      {
        double partial = query.getPartial(id);
        datum.messageForward[0] *= (1 - partial);
        datum.messageForward[1] *= partial;
      }
      else if (query.isTrue(id))
        datum.messageForward[0] = 0;
      else if (query.isFalse(id))
        datum.messageForward[1] = 0;
      else if (query.isUnknown(id))
        datum.freeVariable = true;
      // otherwise, zero neither

      // State tracking is simple for a Variable node
      datum.stateTracking[0] = 0;
      datum.stateTracking[1] = 1;

      // Collect together the message to the neighbor
      for (Connection<ProbabilityGraphNode, ProbabilityGraphEdge> iter : edges)
      {
        Datum neighbor = getAssociatedData(iter.getNode());
        if (neighbor.messageTo == datum)
        {
          datum.messageForward[0] *= neighbor.messageForward[0];
          datum.messageForward[1] *= neighbor.messageForward[1];
        }
      }

      datum.best = (datum.messageForward[0] < datum.messageForward[1]) ? 1 : 0;
      datum.total = datum.messageForward[0] + datum.messageForward[1];
    }

    public void composeMessageReverseVariableNode(Datum datum, GraphQuery query)
    {
      ProbabilityGraphNode node = datum.node;
      NodeEdgeList<ProbabilityGraphNode, ProbabilityGraphEdge> edges = node.getEdges();
      double[] factors = node.getVariableFactors(query);
      double rmsg0 = factors[0] * datum.messageReverse[0];
      double rmsg1 = factors[1] * datum.messageReverse[1];
      // What message to pass will depend on the state of the query
      int id = node.getQueryId();
      if (query.isPartial(id))
      {
        double partial = query.getPartial(id);
        rmsg0 *= (1 - partial);
        rmsg1 *= partial;
      }
      else if (query.isTrue(id))
        rmsg0 = 0;
      else if (query.isFalse(id))
        rmsg1 = 0;

      // Collect together the message to the neighbor
      for (Connection<ProbabilityGraphNode, ProbabilityGraphEdge> iter : edges)
      {
        Datum neighbor = getAssociatedData(iter.getNode());
        if (neighbor.messageTo == datum)
        {
          double m0 = rmsg0;
          double m1 = rmsg1;
          for (Connection<ProbabilityGraphNode, ProbabilityGraphEdge> iter2 : edges)
          {
            Datum neighbor2 = getAssociatedData(iter2.getNode());

            // The message must exclude the contributions from the node that send the message.
            // There is two ways to do this.  We can compute a total and then divide out that
            // message (but that runs to posibility of 0/0) or we can just compute the message for
            // each individually.  We chose the latter.
            if ((iter != iter2) && (neighbor2.messageTo == datum))
            {
              m0 *= neighbor2.messageForward[0];
              m1 *= neighbor2.messageForward[1];
            }
          }
          neighbor.messageReverse[0] = m0;
          neighbor.messageReverse[1] = m1;
        }
      }
    }

    public void composeMessageForwardFactorNode(Datum datum)
    {
      ProbabilityGraphNode node = datum.node;
      NodeEdgeList<ProbabilityGraphNode, ProbabilityGraphEdge> edges = node.getEdges();

      // Initialize the message
      datum.messageForward[0] = -1;
      datum.messageForward[1] = -1;
      datum.stateTracking[0] = 0;
      datum.stateTracking[1] = 1;
      int who = 0;
      double total = 0;

      // Walk the entire factor table
      //   Assumptions the factor table is in the order of the edges (first edge is lowest bit)
      for (int i = 0; i < node.getFactorTableSize(); ++i)
      {
        // take the product of the factors times the messages from the neighbors
        double f = node.getFactor(i);
        int j = 1;
        for (Connection<ProbabilityGraphNode, ProbabilityGraphEdge> connection : edges)
        {
          int to = (i & j) != 0 ? 1 : 0;
          Datum neighbor = getAssociatedData(connection.getNode());
          if (neighbor.messageTo == datum)
          {
            f *= neighbor.messageForward[to]; // incoming message
          }
          else
            who = to;                       // outgoing message
          j <<= 1; // advance j
        }

        // Is it best, if so then pass it as the message
        if (datum.messageForward[who] < f)
        {
          datum.stateTracking[who] = i;
          datum.messageForward[who] = f;
        }
        total += f;
      }

      datum.total = total;
      datum.best = (datum.messageForward[0] < datum.messageForward[1]) ? 1 : 0;
    }

    public void composeMessageReverseFactorNode(Datum datum)
    // Same as the forward, but we need to send one reverse message per edge
    {
      ProbabilityGraphNode node = datum.node;
      NodeEdgeList<ProbabilityGraphNode, ProbabilityGraphEdge> edges = node.getEdges();

      // Walk the entire factor table
      //   Assumptions the factor table is in the order of the edges (first edge is lowest bit)
      // This is the worst book keeping because we have to make one message for each node that send us a message
      int who = 0;
      for (Connection<ProbabilityGraphNode, ProbabilityGraphEdge> iter1 : edges)
      {
        Datum neighbor1 = getAssociatedData(iter1.getNode());

        // We only need to compute reverse messages not forward ones
        if (neighbor1.messageTo != datum)
          continue;

        // Initialize the messages
        neighbor1.messageReverse[0] = 0;
        neighbor1.messageReverse[1] = 0;

        for (int i = 0; i < node.getFactorTableSize(); ++i)
        {
          // take the product of the factors times the messages from the neighbors
          double f = node.getFactor(i);
          int j = 1;
          for (Connection<ProbabilityGraphNode, ProbabilityGraphEdge> iter2 : edges)
          {
            int to = (i & j) != 0 ? 1 : 0;
            Datum neighbor2 = getAssociatedData(iter2.getNode());
            if (neighbor2 == neighbor1)
              who = to;                       // outgoing message
            else if (neighbor2.messageTo == datum)
              f *= neighbor2.messageForward[to]; // incoming message
            else
              f *= datum.messageReverse[to];   // incoming message (from the reverse)
            j <<= 1; // advance j
          }

          // Is it best, if so then pass it as the message
          if (neighbor1.messageReverse[who] < f)
            neighbor1.messageReverse[who] = f;
        }
      }
    }

    public void distributeDecisionVariableNode(Datum datum)
    {
      ProbabilityGraphNode node = datum.node;
      NodeEdgeList<ProbabilityGraphNode, ProbabilityGraphEdge> edges = node.getEdges();

      int best_state = datum.stateTracking[datum.best];
      for (Connection<ProbabilityGraphNode, ProbabilityGraphEdge> iter : edges)
      {
        Datum neighbor = getAssociatedData(iter.getNode());
        if (neighbor.messageTo == datum)
          neighbor.best = best_state;
      }
    }

    public void distributeDecisionFactorNode(Datum datum)
    {
      ProbabilityGraphNode node = datum.node;
      NodeEdgeList<ProbabilityGraphNode, ProbabilityGraphEdge> edges = node.getEdges();

      int best_state = datum.stateTracking[datum.best];
      // update best in each of the neighbors
      int j = 1;
      for (Connection<ProbabilityGraphNode, ProbabilityGraphEdge> iter : edges)
      {
        Datum neighbor = getAssociatedData(iter.getNode());
        if (neighbor.messageTo == datum)
          neighbor.best = ((best_state & j) != 0) ? 1 : 0;
        j <<= 1;
      }
    }

    /**
     * Determines the Datum from the workspace corresponding to a node in the
     * ProbabilityGraph.
     *
     * @param node is the node in the ProbabilityGraph.
     * @return the datum in the workspace.
     * @throws RuntimeException if there is no associated datum for the node
     * which can occur if the workspace is not properly set up.
     */
    public Datum getAssociatedData(ProbabilityGraphNode node)
    {
      if (node == null) //debug
        throw new RuntimeException("access null node");

      Datum datum = data[node.getId()];
      return datum;
    }

    /**
     * Dump the contents of the workspace for debugging.
     *
     * @param os is the stream for the dump.
     */
    public void dump(PrintStream os)
    {
      // display visit order
      {
        os.print("Visit order (by NodeId) ");
        Datum datum = first;
        while (datum != null)
        {
          os.print(datum.node.getId() + " ");
          datum = datum.next;
        }
        os.println();
      }

      // display messages
      int i = 0;
      for (Datum datum : data)
      {
        os.println("Node " + i + " NodeId=" + datum.node.getId());
        os.println("  message to: " + (datum.messageTo != null ? datum.messageTo.node.getId() : -1));
        os.println("  message[0]: " + datum.messageForward[0] + " state=" + datum.stateTracking[0]);
        os.println("  message[1]: " + datum.messageForward[1] + " state=" + datum.stateTracking[1]);
        os.println("  rmessage[0]: " + datum.messageReverse[0]);
        os.println("  rmessage[1]: " + datum.messageReverse[1]);
        os.println("  best: " + datum.best);
        os.println("  ratio: " + datum.ratio);
        os.println();
        i++;
      }
    }

  };

  /**
   * Output holds the results produced by execution of the Viterbi algorithm.
   * These results include the best state, the probability of that state, the
   * variable whose change would make the greatest difference, and the ratio of
   * that difference.
   */
  public static class Output
  {
    // Variables related to max state
    /**
     * State with the highest likelihood.
     */
    public GraphQuery state = null;
    /**
     * Probability for the most likely state.
     */
    public double maxProbability = -1;
    /**
     * Sum of the probability of all states possible for the query.
     */
    public double totalProbability = -1;
    /**
     * QueryId associated with the largest change in likelihood.
     */
    public int pivot;
    /**
     * The ratio between the likelihood with the pivot state altered and the
     * most likely state.
     */
    public double pivotRatio;
  };

  public ViterbiProbabilityGraph()
  {
  }

  /**
   * Query evaluation that preforms only the initialize and collectEvidence
   * steps. This can be used to evaluate the probability of a query without free
   * variables with greater efficiency when a large number of variables are
   * partially specified.
   *
   * @param output used to hold to result of the calculation. Only the
   * maxProbability and totalProbability will be filled out.
   * @param graph holds the ProbabilityGraph to be evaluated.
   * @param query holds the state of the graph.
   */
  public void propogate(Output output, ProbabilityGraph graph, GraphQuery query)
  {
    Workspace workspace = new Workspace();
    propogate(output, graph, query, workspace);
  }

  /**
   * Query evaluation that preforms only the initialize and collectEvidence
   * steps with a user controlled workspace. This can be used to evaluate the
   * probability of a concrete state or computed the maximum probability without
   * the cost of distributing evidence. Use the full version
   * {@link #execute execute} if all result fields are needed.
   *
   * @param output
   * @param graph
   * @param query
   * @param workspace
   */
  public void propogate(Output output, ProbabilityGraph graph, GraphQuery query, Workspace workspace)
  {
    if (output == null)
      throw new RuntimeException("Output not set");

    // Set up all pointers to augment the graph
    initialize(workspace, graph);

    // Assign order of visit
    assignOrder(workspace);

    // Perform the forward message passing
    collectEvidence(output, workspace, query);
  }

  /**
   * Executes the Viterbi algorithm on a ProbabilityGraph with a specified graph
   * stated defined by a query.
   *
   * @param output holds the results of the algorithm.
   * @param graph contains the graph to be evaluated.
   * @param query holds the state of the graph to be queried.
   * @see #execute(Output, ProbabilityGraph, GraphQuery, Workspace)
   */
  public void execute(Output output, ProbabilityGraph graph, GraphQuery query)
  {
    Workspace workspace = new Workspace();
    execute(output, graph, query, workspace);
  }

  /**
   * Executes the Viterbi algorithm on a ProbabilityGraph with a specified graph
   * stated defined by a query.
   *
   * Given a set of variable assumptions with states of true, false, or unknown
   * the Viterbi algorithm computes the maximum likelihood by belief
   * propagation. This version executes the following steps:
   * <ul>
   * <li> {@link #initialize initialize}
   * <li> {@link #assignOrder assignOrder}
   * <li> {@link #collectEvidence collectEvidence}
   * <li> {@link #distrubuteDecisions distrubuteDecisions}
   * <li> {@link #distrubuteEvidence distrubuteEvidence}
   * <li> {@link #calculateStateRatios calculateStateRatios}
   * </ul>
   *
   * @param output holds the results of the algorithm.
   * @param graph contains the graph to be evaluated.
   * @param query holds the state of the graph to be queried.
   * @param workspace is the workspace for calculations.
   */
  public void execute(
          Output output,
          ProbabilityGraph graph,
          GraphQuery query,
          Workspace workspace)
  {
    if (output == null)
      throw new RuntimeException("Output not set");

    // Set up all pointers to augment the graph
    initialize(workspace, graph);

    // Assign order of visit
    assignOrder(workspace);

    // Perform the forward message passing
    collectEvidence(output, workspace, query);

    // Find the best state 
    distrubuteDecisions(output, workspace);

    // Find the biggest change
    distrubuteEvidence(workspace, query);

    // Compute the state ratios to find the next pivot
    calculateStateRatios(output, workspace);
  }

  /**
   * Sets up a workspace for the Viterbi algorithm to operate on a
   * ProbabilityGraph.
   *
   * @param workspace is the workspace to be populated.
   * @param graph is the ProbabilityGraph to be operated on.
   */
  public void initialize(Workspace workspace, ProbabilityGraph graph)
  {
    // Resize the augmentation data to meet the requirements of the graph
    workspace.initialize(graph);
  }

  /**
   * Operates on a workspace to decide the order for nodes to be processed by
   * the Viterbi algorithm. This method can be called after
   * {@link #initialize initialize}.
   *
   * @param workspace holds the graph being processed and the Viterbi working
   * variables.
   */
  public void assignOrder(Workspace workspace)
  {
    // Search for any nodes that have zero or one neighbor and queue them for processing
    for (Datum datum : workspace.data)
    {
      if (datum.unvisitedNeighbors < 2)
        workspace.enqueue(datum);
    }

    Datum current = workspace.first;
    while (current != null)
    {
      ProbabilityGraphNode node = current.node;
      NodeEdgeList<ProbabilityGraphNode, ProbabilityGraphEdge> edges = node.getEdges();

      // Decrement the neighbor count and enqueue nodes that are ready
      for (Connection<ProbabilityGraphNode, ProbabilityGraphEdge> iter : edges)
      {
        Datum neighbor = workspace.getAssociatedData(iter.getNode());
        if (neighbor.messageTo == null)
        {
          // Assumption the node only has zero or one unvisited neighbor
          // thus once we find one, we can bail
          neighbor.unvisitedNeighbors--;
          current.messageTo = neighbor;
          if (neighbor.unvisitedNeighbors < 2)
            workspace.enqueue(neighbor);
          break;
        }
      }

      current = current.next;
    }

    // If any nodes still have 2 neighbors by this point we have a loop.  
    // We should throw an exception
    for (Datum datum : workspace.data)
    {
      if (datum.unvisitedNeighbors > 1)
        throw new RuntimeException("bad graph: loop detected");
    }
  }

  /**
   * Propagates the evidence from the query to the ProbabilityGraph. Produces
   * the forward messages from the edges of the graph to the central most node.
   * This algorithm can be called after {@link #assignOrder assignOrder}. After
   * completion the probability of the most likely state and total probability
   * of all possible states will have been computed.
   *
   * @param output holds the results of the process.
   * @param workspace holds the ProbabilityGraph and working variables.
   * @param query holds the state to be evaluated.
   */
  public void collectEvidence(Output output, Workspace workspace, GraphQuery query)
  {
    workspace.querySize = query.size();

    // To handle forests, we need an independent counter for the global maximum
    double max = 1;
    double total = 1;

    // Process the graph (FORWARD)
    Datum datum = workspace.first;
    while (datum != null)
    {
      // Pop the front item
      ProbabilityGraphNode node = datum.node;

      // Probability graph has two node types, Variable Nodes and Factor nodes
      if (node.isVariableNode())
        workspace.composeMessageForwardVariableNode(datum, query);
      else if (node.isFactorNode())
        workspace.composeMessageForwardFactorNode(datum);
      else
        throwBadNodeType(node);

      // track the global maximum
      if (datum.messageTo == null)
      {
        max *= datum.messageForward[datum.best];
        total *= datum.total;
      }

      datum = datum.next;
    } // while

    // We now have the probability for the best possible solution
    output.maxProbability = max;
    output.totalProbability = total;
  }

  /**
   * Extracts the best state of a graph for a query. This method can be called
   * only after evidence has been collected for the given query. Updates the
   * state variable in the output to reflect the most likely state given the
   * evidence. This method can be called after
   * {@link #collectEvidence collectEvidence}.
   *
   * @param output holds the results from the algorithm.
   * @param workspace holds the ProbabilityGraph and working variables.
   */
  public void distrubuteDecisions(Output output, Workspace workspace)
  {
    output.state = new GraphQuery(workspace.querySize);

    // Process the queue in reverse ( to find the best state )
    Datum current = workspace.last;
    while (current != null)
    {
      ProbabilityGraphNode node = current.node;

      // Process the node (and copy the state into output)
      if (node.isVariableNode())
      {
        workspace.distributeDecisionVariableNode(current);
        if (current.best == 1)
          output.state.setTrue(node.getQueryId());
        else
          output.state.setFalse(node.getQueryId());
      }
      else if (node.isFactorNode())
        workspace.distributeDecisionFactorNode(current);
      else
        throwBadNodeType(node);

      // back track
      current = current.previous;
    }
  }

  /**
   * Updates the probabilities of a graph based on the belief propagation so
   * that the pivot node can be found. This method can be called after the
   * decisions have been distributed with {@link #distrubuteDecisions
   * distributeDecisions}. This step is only necessary if the pivot node needs
   * to be located. The query must be the same as the current evaluated state.
   * <p>
   *
   * @param workspace is the working variables to be updated based on the
   * decision.
   * @param query is state of the probability graph that was evaluated.
   */
  public void distrubuteEvidence(Workspace workspace, GraphQuery query)
  {
    // Process the queue in reverse ( to find the best state )
    Datum current = workspace.last;
    while (current != null)
    {
      ProbabilityGraphNode node = current.node;

      if (node.isVariableNode())
        workspace.composeMessageReverseVariableNode(current, query);
      else if (node.isFactorNode())
        workspace.composeMessageReverseFactorNode(current);
      else
        throwBadNodeType(node);

      // back track
      current = current.previous;
    }
  }

  /**
   * Determines the pivot node and the likelihood ratio associated with it.
   * After evidence has been distributed, the Viterbi algorithm can compute the
   * node that was free in the query that would be made the greatest change in
   * the probability. This method is called after
   * {@link #distrubuteEvidence distributeEvidence}.
   *
   * @param output holds the results of this
   * @param workspace holds the ProbabiltyGraph and working variables.
   */
  public void calculateStateRatios(Output output, Workspace workspace)
  {
    // Process the queue 
    Datum current = workspace.first;
    output.pivot = -1;
    output.pivotRatio = -1;
    while (current != null)
    {
      if (current.freeVariable)
      {
        ProbabilityGraphNode node = current.node;
        // We can compute the ratio of the state with this variable false 
        // verses true. The product of the forward and reverse messages is the 
        // probability of the most probable state 
        double best_false = current.messageForward[0] * current.messageReverse[0];
        double best_true = current.messageForward[1] * current.messageReverse[1];
        if (best_true == 0)
          current.ratio = 1e308;
        else
          current.ratio = best_false / best_true;
        if (current.ratio > output.pivotRatio)
        {
          output.pivotRatio = current.ratio;
          output.pivot = node.getQueryId();
        }
      }
      current = current.next;
    }
  }

  private static void throwBadNodeType(ProbabilityGraphNode node)
  {
    throw new RuntimeException("bad node type " + node + " " + node.type);
  }
}
