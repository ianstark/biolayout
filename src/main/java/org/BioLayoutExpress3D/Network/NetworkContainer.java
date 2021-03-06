package org.BioLayoutExpress3D.Network;

import java.awt.*;
import java.util.*;
import org.BioLayoutExpress3D.CoreUI.*;
import org.BioLayoutExpress3D.CoreUI.Dialogs.*;
import org.BioLayoutExpress3D.Graph.GraphElements.*;
import static org.BioLayoutExpress3D.Environment.GlobalEnvironment.*;
import static org.BioLayoutExpress3D.DebugConsole.ConsoleOutput.*;

/**
*  org.BioLayoutExpress3D.Network.NetworkContainer
*
*  Created by CGG EBI on Wed Aug 07 2002.
*
* @author Full refactoring by Thanos Theo, 2008-2009
* @version 3.0.0.0
*
*/

public abstract class NetworkContainer
{
    public static final float CANVAS_X_SIZE = 1000.0f;
    public static final float CANVAS_Y_SIZE = 1000.0f;
    public static final float CANVAS_Z_SIZE = 1000.0f;

    // static so as to instantiate only one instance for all network containers
    protected static FRLayout frLayout = null;

    protected LayoutFrame layoutFrame = null;
    protected LayoutClassSetsManager layoutClassSetsManager = null;
    protected HashMap<String, Vertex> verticesMap = null;
    protected ArrayList<Edge> edges = null;
    protected boolean isOptimized = false;
    protected boolean isRelayout = false;

    // Variable used for the graphml network container
    private GraphmlNetworkContainer gnc = null;

    // static initializer so as to initialize the FRLayout before the NetworkContainer constructor
    static
    {
        frLayout = new FRLayout(CANVAS_X_SIZE, CANVAS_Y_SIZE, CANVAS_Z_SIZE);
    }

    public NetworkContainer(LayoutClassSetsManager layoutClassSetsManager, LayoutFrame layoutFrame)
    {
        this.layoutClassSetsManager = layoutClassSetsManager;
        this.layoutFrame = layoutFrame;

        verticesMap = new HashMap<String, Vertex>();
        edges = new ArrayList<Edge>();
    }

    public void setOptimized(boolean isOptimized)
    {
        this.isOptimized = isOptimized;
    }

    /**
    *  To be used for loading normal layout saved files.
    */
    public void addNetworkConnection(String first, String second, float weight)
    {
        Vertex vertex1 = null;
        Vertex vertex2 = null;

        if ( verticesMap.containsKey(first) )
        {
            vertex1 = verticesMap.get(first);
        }
        else
        {
            vertex1 = new Vertex(first, this);
            verticesMap.put(first, vertex1);
        }

        if ( verticesMap.containsKey(second) )
        {
            vertex2 = verticesMap.get(second);
        }
        else
        {
            vertex2 = new Vertex(second, this);
            verticesMap.put(second, vertex2);
        }

        if ( !vertex1.getEdgeConnectionsMap().containsKey(vertex2) )
        {
            Edge edge = new Edge(vertex1, vertex2, weight);
            vertex1.addConnection(vertex2, edge);
            vertex2.addConnection(vertex1, edge);

            edges.add(edge);
        }
    }

    /**
    *  To be used for loading an SPN layout saved file.
    */
    public void addNetworkConnection(String first, String second, String edgeName, boolean isTotalInhibitorEdge, boolean isPartialInhibitorEdge, boolean hasDualArrowHead)
    {
        Vertex vertex1 = null;
        Vertex vertex2 = null;

        if ( verticesMap.containsKey(first) )
        {
            vertex1 = verticesMap.get(first);
        }
        else
        {
            vertex1 = new Vertex(first, this);
            verticesMap.put(first, vertex1);
        }

        if ( verticesMap.containsKey(second) )
        {
            vertex2 = verticesMap.get(second);
        }
        else
        {
            vertex2 = new Vertex(second, this);
            verticesMap.put(second, vertex2);
        }

        if ( !vertex1.getEdgeConnectionsMap().containsKey(vertex2) )
        {
            float weight = (isTotalInhibitorEdge) ? 1.0f : (isPartialInhibitorEdge) ? 0.5f : 0.0f;
            Edge edge = new Edge(edgeName, vertex1, vertex2, weight, isTotalInhibitorEdge, isPartialInhibitorEdge, hasDualArrowHead);
            vertex1.addConnection(vertex2, edge);
            vertex2.addConnection(vertex1, edge);

            edges.add(edge);
        }
    }

    public void initGraphmlNetworkContainer()
    {
        gnc = new GraphmlNetworkContainer(this, layoutClassSetsManager);
    }

    public boolean getIsGraphml()
    {
        return (gnc == null) ? false : gnc.getIsGraphml();
    }

    public boolean getIsPetriNet()
    {
        return (gnc == null) ? false : gnc.getIsPetriNet();
    }

    public boolean getHasStandardPetriNetTransitions()
    {
        return (gnc == null) ? false : gnc.getHasStandardPetriNetTransitions();
    }

    /**
    *  Gets the node name from the node.
    */
    public String getNodeName(String nodeName)
    {
        return ( (gnc != null) && gnc.getIsGraphml() ) ? gnc.getNodeName(nodeName) : nodeName;
    }

    /**
    *  Sets the node name for the node.
    */
    public void setNodeName(GraphNode node, String newNodeName)
    {
        if ( (gnc != null) && gnc.getIsGraphml() )
            gnc.setNodeName(node, newNodeName);
        else
            node.setNodeName(newNodeName);
    }

    public void optimize(GraphLayoutAlgorithm gla)
    {
        if (DEBUG_BUILD) println("Optimizing");

        if (WEIGHTED_EDGES)
        {
            normaliseWeights();
        }

        float initialTemperature = frLayout.getTemperature();
        int numberOfIterations = 0;

        LayoutProgressBarDialog layoutProgressBarDialog = layoutFrame.getLayoutProgressBar();
        // for now both N-CP & OpenCL methods use N-CP for Burst Iterations,as Burst Iterations for the OpenCL version are not implemented
        String progressBarParallelismTitle = ( ( USE_MULTICORE_PROCESS && USE_LAYOUT_N_CORE_PARALLELISM.get() ) || ( OPENCL_GPU_COMPUTING_ENABLED && USE_OPENCL_GPU_COMPUTING_LAYOUT_CALCULATION.get() ) ) ? " (Utilizing " + NUMBER_OF_AVAILABLE_PROCESSORS + "-Core Parallelism)" : "";

        if (isRelayout)
        {
            numberOfIterations = BURST_LAYOUT_ITERATIONS.get();
            frLayout.setTemperature((frLayout.getTemperature() * numberOfIterations) / frLayout.getNumberOfIterations());

            layoutProgressBarDialog.prepareProgressBar(numberOfIterations, "Now Processing Burst Layout Iterations" + progressBarParallelismTitle + "...");
        }
        else
        {
            numberOfIterations = frLayout.getNumberOfIterations();
            layoutProgressBarDialog.prepareProgressBar(numberOfIterations,
                    "Now Processing Layout Iterations" + progressBarParallelismTitle + "...", true);
        }

        layoutProgressBarDialog.startProgressBar();

        if (!isOptimized)
        {
            if (!RENDERER_MODE_3D)
            {
                // cannot do all iterations with allIterationsCalcBiDirForce2D() as the native code cannot refresh (show) the iteration on the OpenGL display (freezes)
                while (--numberOfIterations >= 0)
                {
                    frLayout.iterateCalcBiDirForce2D();
                    updateGUI(layoutProgressBarDialog);
                }
            }
            else
            {
                // cannot do all iterations with allIterationsCalcBiDirForce3D() as the native code cannot refresh (show) the iteration on the OpenGL display (freezes)
                while (--numberOfIterations >= 0)
                {
                    frLayout.iterateCalcBiDirForce3D();
                    updateGUI(layoutProgressBarDialog);
                }
            }

            // applying the new vertex points at the end of the layout algoprithm process
            frLayout.setPointsToVertices();
            frLayout.setTemperature(initialTemperature);
            frLayout.clean();
            layoutFrame.getGraph().rebuildGraph();
        }
        else
        {
            isOptimized = false;
        }

        layoutProgressBarDialog.endProgressBar();
        if (isRelayout) layoutProgressBarDialog.stopProgressBar();
    }

    private void updateGUI(LayoutProgressBarDialog layoutProgressBarDialog)
    {
        if ( SHOW_LAYOUT_ITERATIONS.get() )
        {
            // applying the new vertex points here so as to be renderered below with the rebuildGraph() method call
            frLayout.setPointsToVertices();
            layoutFrame.getGraph().rebuildGraph();
        }

        layoutProgressBarDialog.incrementProgress();
    }

    public void clear()
    {
        verticesMap.clear();
        edges.clear();

        layoutClassSetsManager.clearClassSets();
        WEIGHTED_EDGES = false;
        isOptimized = false;

        if (gnc != null)
            gnc.clear();
        gnc = null;

        layoutFrame.getGraph().getSelectionManager().clearGraphUndoDelete();

        System.gc();
    }

    public void updateVertexLocation(String vertexName, float valueX, float valueY, float valueZ)
    {
        Vertex vertex = verticesMap.get(vertexName);
        if (vertex != null)
        {
            vertex.setVertexLocation(valueX, valueY, valueZ);
            isOptimized = true;
        }
    }

    /**
    /* Overload updateVertexLocation for 4D coordinates
     */

    public void updateVertexLocation(String vertexName, float valueX, float valueY, float valueZ, float valueW)
    {
	Vertex vertex = verticesMap.get(vertexName);
	if (vertex != null)
	{
	    vertex.setVertexLocation(valueX, valueY, valueZ, valueW);
	    isOptimized = true;
	}
    }
    
    public void normaliseWeights()
    {
        if (DEBUG_BUILD) println("Normalizing Weights");

        float max = 0.0f;
        float min = Float.MAX_VALUE;

        for (Edge edge : edges)
        {
            if (edge.getWeight() >= max)
                max = edge.getWeight();

            if (edge.getWeight() < min)
                min = edge.getWeight();
        }

        for (Edge edge : edges)
        {
            if (max != min)
            {
                edge.setScaledWeight( ( (edge.getWeight() - min) / (max - min) ) );
                edge.setNormalisedWeight( WEIGHT_LEVEL * ( ( (edge.getWeight() - min) / (max - min) ) - 0.5f ) );
                edge.setNormalisedWeight( (float)Math.pow( 2.0, edge.getNormalisedWeight() ) );
            }
            else
            {
                edge.setScaledWeight( 1.0f );
                edge.setNormalisedWeight( 1.0f );
            }
        }
    }

    public abstract void optimize(int componentNo);

    public void relayout(GraphLayoutAlgorithm gla)
    {
        isOptimized = false;
        isRelayout = true;

        if ( layoutFrame.getGraph().getSelectionManager().getUndeleteAllNodesAction().isEnabled() )
            layoutFrame.getGraph().getSelectionManager().undeleteAllNodes();

        optimize(gla);
        isRelayout = false;
    }

    public int getNumberOfVertices()
    {
        return verticesMap.values().size();
    }

    public Collection<Vertex> getVertices()
    {
        return verticesMap.values();
    }

    public ArrayList<Edge> getEdges()
    {
        return edges;
    }

    public LayoutClassSetsManager getLayoutClassSetsManager()
    {
        return layoutClassSetsManager;
    }

    public HashMap<String, Vertex> getVerticesMap()
    {
        return verticesMap;
    }

    public double getKValue()
    {
        return frLayout.getKValue();
    }

    public FRLayout getFRLayout()
    {
        return frLayout;
    }

    public GraphmlNetworkContainer getGraphmlNetworkContainer()
    {
        return gnc;
    }


}
