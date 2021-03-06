package soot.jimple.infoflow.data.pathBuilders;

import heros.solver.CountingThreadPoolExecutor;
import heros.solver.Pair;

import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.jimple.Stmt;
import soot.jimple.infoflow.InfoflowResults;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.data.AbstractionAtSink;
import soot.jimple.infoflow.data.SourceContextAndPath;
import soot.jimple.infoflow.solver.IInfoflowCFG;

/**
 * Class for reconstructing abstraction paths from sinks to source. This builder
 * is context-sensitive which makes it more precise than the
 * {@link ContextInsensitivePathBuilder}, but also a bit slower.
 * 
 * @author Steven Arzt
 */
public class ContextSensitivePathBuilder extends AbstractAbstractionPathBuilder {
	
	private AtomicInteger propagationCount = null;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final InfoflowResults results = new InfoflowResults();
	private final CountingThreadPoolExecutor executor;
	
	private boolean reconstructPaths = false;
		
	/**
	 * Creates a new instance of the {@link ContextSensitivePathBuilder} class
	 * @param maxThreadNum The maximum number of threads to use
	 */
	public ContextSensitivePathBuilder(IInfoflowCFG icfg, int maxThreadNum) {
		super(icfg);
        int numThreads = Runtime.getRuntime().availableProcessors();
		this.executor = createExecutor(maxThreadNum == -1 ? numThreads
				: Math.min(maxThreadNum, numThreads));
	}
	
	/**
	 * Creates a new executor object for spawning worker threads
	 * @param numThreads The number of threads to use
	 * @return The generated executor
	 */
	private CountingThreadPoolExecutor createExecutor(int numThreads) {
		return new CountingThreadPoolExecutor
				(numThreads, Integer.MAX_VALUE, 30, TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>());
	}
	
	/**
	 * Task for tracking back the path from sink to source.
	 * 
	 * @author Steven Arzt
	 */
	private class SourceFindingTask implements Runnable {
		private final Abstraction abstraction;
		
		public SourceFindingTask(Abstraction abstraction) {
			this.abstraction = abstraction;
		}
		
		@Override
		public void run() {
			propagationCount.incrementAndGet();
			
			final Set<SourceContextAndPath> paths = abstraction.getPaths();
			final Abstraction pred = abstraction.getPredecessor();
			
			if (pred == null) {
				// If we have no predecessors, this must be a source
				assert abstraction.getSourceContext() != null;
				assert abstraction.getNeighbors() == null;
				
				// Register the result
				for (SourceContextAndPath scap : paths) {
					SourceContextAndPath extendedScap =
							scap.extendPath(abstraction.getSourceContext().getStmt());
					results.addResult(extendedScap.getValue(),
							extendedScap.getStmt(),
							abstraction.getSourceContext().getValue(),
							abstraction.getSourceContext().getStmt(),
							abstraction.getSourceContext().getUserData(),
							extendedScap.getPath());
				}
			}
			else {
				for (SourceContextAndPath scap : paths) {						
					// Process the predecessor
					if (processPredecessor(scap, pred))
						// Schedule the predecessor
						executor.execute(new SourceFindingTask(pred));
					
					// Process the predecessor's neighbors
					if (pred.getNeighbors() != null)
						for (Abstraction neighbor : pred.getNeighbors())
							if (processPredecessor(scap, neighbor))
								// Schedule the predecessor
								executor.execute(new SourceFindingTask(neighbor));
				}
			}
		}

		private boolean processPredecessor(SourceContextAndPath scap, Abstraction pred) {
			// Shortcut: If this a call-to-return node, we should not enter and
			// immediately leave again for performance reasons.
			if (pred.getCurrentStmt() != null
					&& pred.getCurrentStmt() == pred.getCorrespondingCallSite()) {
				SourceContextAndPath extendedScap = scap.extendPath(reconstructPaths
						? pred.getCurrentStmt() : null);
				return pred.addPathElement(extendedScap);
			}
			
			// If we enter a method, we put it on the stack
			SourceContextAndPath extendedScap = scap.extendPath(reconstructPaths
					? pred.getCurrentStmt() : null, pred.getCorrespondingCallSite());
			
			// Do we process a method return?
			if (pred.getCurrentStmt() != null 
					&& pred.getCurrentStmt().containsInvokeExpr()) {
				// Pop the top item off the call stack. This gives us the item
				// and the new SCAP without the item we popped off.
				Pair<SourceContextAndPath, Stmt> pathAndItem =
						extendedScap.popTopCallStackItem();
				if (pathAndItem != null) {
					Stmt topCallStackItem = pathAndItem.getO2();
					// Make sure that we don't follow an unrealizable path
					if (topCallStackItem != pred.getCurrentStmt())
						return false;
					
					// We have returned from a function
					extendedScap = pathAndItem.getO1();
				}
			}
				
			// Add the new path
			return pred.addPathElement(extendedScap);
		}
	}
	
	@Override
	public void computeTaintSources(final Set<AbstractionAtSink> res) {
		this.reconstructPaths = false;
		runSourceFindingTasks(res);
	}
	
	@Override
	public void computeTaintPaths(final Set<AbstractionAtSink> res) {
		this.reconstructPaths = true;
		runSourceFindingTasks(res);
	}
	
	private void runSourceFindingTasks(final Set<AbstractionAtSink> res) {
		if (res.isEmpty())
			return;
		
		long beforePathTracking = System.nanoTime();
		propagationCount = new AtomicInteger();
    	logger.info("Obtainted {} connections between sources and sinks", res.size());
    	
    	// Start the propagation tasks
    	int curResIdx = 0;
    	for (final AbstractionAtSink abs : res) {
    		logger.info("Building path " + ++curResIdx);
   			buildPathForAbstraction(abs);
   			
   			// Also build paths for the neighbors of our result abstraction
   			if (abs.getAbstraction().getNeighbors() != null)
   				for (Abstraction neighbor : abs.getAbstraction().getNeighbors()) {
   					AbstractionAtSink neighborAtSink = new AbstractionAtSink(neighbor,
   							abs.getSinkValue(), abs.getSinkStmt());
   		   			buildPathForAbstraction(neighborAtSink);
   				}
    	}

    	try {
			executor.awaitCompletion();
		} catch (InterruptedException ex) {
			logger.error("Could not wait for path executor completion: {0}", ex.getMessage());
			ex.printStackTrace();
		}
    	
    	logger.info("Path processing took {} seconds in total for {} edges",
    			(System.nanoTime() - beforePathTracking) / 1E9, propagationCount.get());
	}
	
	/**
	 * Builds the path for the given abstraction that reached a sink
	 * @param abs The abstraction that reached a sink
	 */
	private void buildPathForAbstraction(final AbstractionAtSink abs) {
		SourceContextAndPath scap = new SourceContextAndPath(
				abs.getSinkValue(), abs.getSinkStmt());
		scap = scap.extendPath(abs.getSinkStmt());
		abs.getAbstraction().addPathElement(scap);
		
		executor.execute(new SourceFindingTask(abs.getAbstraction()));
	}
	
	@Override
	public void shutdown() {
    	executor.shutdown();		
	}

	@Override
	public InfoflowResults getResults() {
		return this.results;
	}

}
