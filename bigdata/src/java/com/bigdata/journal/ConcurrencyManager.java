package com.bigdata.journal;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.Logger;

import com.bigdata.btree.BTree;
import com.bigdata.concurrent.LockManager;
import com.bigdata.counters.CounterSet;
import com.bigdata.counters.ICounterSet;
import com.bigdata.counters.Instrument;
import com.bigdata.resources.StoreManager;
import com.bigdata.service.IBigdataClient;
import com.bigdata.service.IServiceShutdown;
import com.bigdata.util.concurrent.DaemonThreadFactory;
import com.bigdata.util.concurrent.QueueStatisticsTask;
import com.bigdata.util.concurrent.TaskCounters;

/**
 * Supports concurrent operations against named indices. Historical read and
 * read-committed tasks run with full concurrency. For unisolated tasks, the
 * {@link ConcurrencyManager} uses a {@link LockManager} to identify a schedule
 * of operations such that access to an unisolated named index is always single
 * threaded while access to distinct unisolated named indices MAY be concurrent.
 * <p>
 * There are several thread pools that facilitate concurrency. They are:
 * <dl>
 * 
 * <dt>{@link #readService}</dt>
 * <dd>Concurrent historical and read-committed tasks are run against a
 * <strong>historical</strong> view of a named index using this service. No
 * locking is imposed. Concurrency is limited by the size of the thread pool.</dd>
 * 
 * <dt>{@link #writeService}</dt>
 * <dd>Concurrent unisolated writers running against the <strong>current</strong>
 * view of (or more more) named index(s) (the "live" or "mutable" index(s)). The
 * underlying {@link BTree} is NOT thread-safe for writers. Therefore writers
 * MUST predeclare their locks, which allows us to avoid deadlocks altogether.
 * This is also used to schedule the commit phrase of transactions (transaction
 * commits are in fact unisolated tasks).</dd>
 * 
 * <dt>{@link #txWriteService}</dt>
 * <dd>
 * <p>
 * This is used for the "active" phrase of transaction. Transactions read from
 * historical states of named indices during their active phase and buffer the
 * results on isolated indices backed by a per-transaction
 * {@link TemporaryStore}. Since transactions never write on the unisolated
 * indices during their "active" phase, distinct transactions may be run with
 * arbitrary concurrency. However, concurrent tasks for the same transaction
 * must obtain an exclusive lock on the isolated index(s) that are used to
 * buffer their writes.
 * </p>
 * <p>
 * A transaction that requests a commit using the
 * {@link ITransactionManagerService} results in a unisolated task being
 * submitted to the {@link #writeService}. Transactions are selected to commit
 * once they have acquired a lock on the corresponding unisolated indices,
 * thereby enforcing serialization of their write sets both among other
 * transactions and among unisolated writers. The commit itself consists of the
 * standard validation and merge phrases.
 * </p>
 * </dd>
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class ConcurrencyManager implements IConcurrencyManager {

    final protected static Logger log = Logger.getLogger(ConcurrencyManager.class);
    
    /**
     * True iff the {@link #log} level is INFO or less.
     */
    final protected static boolean INFO = log.isInfoEnabled();

    /**
     * True iff the {@link #log} level is DEBUG or less.
     */
    final protected static boolean DEBUG = log.isDebugEnabled();
    
    /**
     * Options for the {@link ConcurrentManager}.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public static interface Options extends IServiceShutdown.Options {

        /**
         * The #of threads in the pool handling concurrent transactions.
         * 
         * @see #DEFAULT_TX_SERVICE_CORE_POOL_SIZE
         */
        String TX_SERVICE_CORE_POOL_SIZE = ConcurrencyManager.class.getName()
                + ".txService.corePoolSize";

        /**
         * The default #of threads in the transaction service thread pool.
         */
        String DEFAULT_TX_SERVICE_CORE_POOL_SIZE = "0";

        /**
         * The #of threads in the pool handling concurrent unisolated read
         * requests on named indices -or- ZERO (0) if the size of the thread
         * pool is not fixed (default is <code>0</code>).
         * 
         * @see #DEFAULT_READ_SERVICE_CORE_POOL_SIZE
         */
        String READ_SERVICE_CORE_POOL_SIZE = ConcurrencyManager.class.getName()
                + ".readService.corePoolSize";

        /**
         * The default #of threads in the read service thread pool.
         * 
         * @see #READ_SERVICE_CORE_POOL_SIZE
         */
        String DEFAULT_READ_SERVICE_CORE_POOL_SIZE = "0";

        /**
         * The minimum #of threads in the pool handling concurrent unisolated
         * write on named indices (default is
         * {@value #DEFAULT_WRITE_SERVICE_CORE_POOL_SIZE}). The size of the
         * thread pool will automatically grow to meet the possible concurrency
         * of the submitted tasks up to the configured
         * {@link #WRITE_SERVICE_MAXIMUM_POOL_SIZE}.
         * <p>
         * The main factor that influences the throughput of group commit is the
         * <em>potential</em> concurrency of the submitted
         * {@link ITx#UNISOLATED} tasks (both how many can be submitted in
         * parallel by the application and how many can run concurrency - tasks
         * writing on the same index have a shared dependency and can not run
         * concurrently).
         * <p>
         * Each {@link ITx#UNISOLATED} task that completes processing will block
         * until the next commit, thereby absorbing a worker thread. For this
         * reason, it can improve performance if you set the core pool size and
         * the maximum pool size for the write service <em>higher</em> that
         * the potential concurrency with which the submitted tasks could be
         * processed.
         * <p>
         * There is also a strong effect as the JVM performs optimizations on
         * the running code, so randomize your tests. See
         * {@link StressTestGroupCommit} for performance tuning.
         * 
         * @see #DEFAULT_WRITE_SERVICE_CORE_POOL_SIZE
         */
        String WRITE_SERVICE_CORE_POOL_SIZE = ConcurrencyManager.class
                .getName()
                + ".writeService.corePoolSize";

        /**
         * The default minimum #of threads in the write service thread pool.
         */
        String DEFAULT_WRITE_SERVICE_CORE_POOL_SIZE = "10";

        /**
         * The maximum #of threads allowed in the pool handling concurrent
         * unisolated write on named indices (default is
         * {@value #DEFAULT_WRITE_SERVICE_CORE_POOL_SIZE}). Since each task
         * that completes processing will block until the next group commit,
         * this value places an absolute upper bound on the number of tasks in a
         * commit group. However, many other factors (client concurrency, index
         * locks, task latency, CPU and IO utilization, etc.) can reduce the
         * actual group commit size.
         * 
         * @see #DEFAULT_WRITE_SERVICE_MAXIMUM_POOL_SIZE
         */
        String WRITE_SERVICE_MAXIMUM_POOL_SIZE = ConcurrencyManager.class
                .getName()
                + ".writeService.maximumPoolSize";

        /**
         * The default for the maximum #of threads in the write service thread
         * pool.
         */
        String DEFAULT_WRITE_SERVICE_MAXIMUM_POOL_SIZE = "50";

        /**
         * The time in milliseconds that the {@link WriteExecutorService} will
         * keep alive excess worker threads (those beyond the core pool size).
         */
        String WRITE_SERVICE_KEEP_ALIVE_TIME = ConcurrencyManager.class
                .getName()
                + ".writeService.keepAliveTime";

        String DEFAULT_WRITE_SERVICE_KEEP_ALIVE_TIME = "60000";

        /**
         * When true, the write service will be prestart all of its worker
         * threads (default
         * {@value #DEFAULT_WRITE_SERVICE_PRESTART_ALL_CORE_THREADS}).
         * 
         * @see #DEFAULT_WRITE_SERVICE_PRESTART_ALL_CORE_THREADS
         */
        String WRITE_SERVICE_PRESTART_ALL_CORE_THREADS = ConcurrencyManager.class
                .getName()
                + ".writeService.prestartAllCoreThreads";

        /**
         * The default for {@link #WRITE_SERVICE_PRESTART_ALL_CORE_THREADS}.
         */
        String DEFAULT_WRITE_SERVICE_PRESTART_ALL_CORE_THREADS = "false";

        /**
         * The maximum depth of the write service queue before newly submitted
         * tasks will block the caller -or- ZERO (0) to use a queue with an
         * unlimited capacity.
         * 
         * @see #DEFAULT_WRITE_SERVICE_QUEUE_CAPACITY
         */
        String WRITE_SERVICE_QUEUE_CAPACITY = ConcurrencyManager.class
                .getName()
                + ".writeService.queueCapacity";

        /**
         * The default maximum depth of the write service queue (1000).
         */
        String DEFAULT_WRITE_SERVICE_QUEUE_CAPACITY = "1000";

        /**
         * The timeout in milliseconds that the the {@link WriteExecutorService}
         * will await other tasks to join the commit group (default
         * {@value #DEFAULT_WRITE_SERVICE_GROUP_COMMIT_TIMEOUT}). When ZERO
         * (0), group commit is disabled since the first task to join the commit
         * group will NOT wait for other tasks and the commit group will
         * therefore always consist of a single task.
         * 
         * @see #DEFAULT_WRITE_SERVICE_GROUP_COMMIT_TIMEOUT
         */
        String WRITE_SERVICE_GROUP_COMMIT_TIMEOUT = ConcurrencyManager.class
                .getName()
                + ".writeService.groupCommitTimeout";

        String DEFAULT_WRITE_SERVICE_GROUP_COMMIT_TIMEOUT = "100";

    }

    /**
     * The properties specified to the ctor.
     */
    final private Properties properties;

    /**
     * The object managing local transactions. 
     */
    final private ILocalTransactionManager transactionManager;
    
    /**
     * The object managing the resources on which the indices are stored.
     */
    final private IResourceManager resourceManager;
    
    /**
     * The local time at which this service was started.
     */
    final private long serviceStartTime = System.currentTimeMillis();
    
    /**
     * <code>true</code> until the service is shutdown.
     */
    private boolean open = true;
    
    /**
     * Pool of threads for handling concurrent read/write transactions on named
     * indices. Distinct transactions are not inherently limited in their
     * concurrency, but concurrent operations within a single transaction MUST
     * obtain an exclusive lock on the isolated index(s) on the temporary store.
     * The size of the thread pool for this service governs the maximum
     * practical concurrency for transactions.
     * <p>
     * Transactions always read from historical data and buffer their writes
     * until they commit. Transactions that commit MUST acquire unisolated
     * writable indices for each index on which the transaction has written.
     * Once the transaction has acquired those writable indices it then runs its
     * commit phrase as an unisolated operation on the {@link #writeService}.
     */
    final protected ThreadPoolExecutor txWriteService;

    /**
     * Pool of threads for handling concurrent unisolated read operations on
     * named indices using <strong>historical</strong> data. Unisolated
     * read operations from historical data are not inherently limited in
     * their concurrency and do not conflict with unisolated writers. The
     * size of the thread pool for this service governs the maximum
     * practical concurrency for unisolated readers.
     * <p>
     * Note that unisolated read operations on the <strong>current</strong>
     * state of an index DO conflict with unisolated writes and such tasks
     * must be run as unisolated writers.
     * <p>
     * Note: unisolated readers of historical data do require the rention of
     * historical commit records (which may span more than one logical
     * journal) until the reader terminates.
     */
    final protected ThreadPoolExecutor readService;

    /**
     * Pool of threads for handling concurrent unisolated write operations on
     * named indices. Unisolated writes are always performed against the current
     * state of the named index. Unisolated writes for the same named index (or
     * index partition) conflict and must be serialized. The size of this thread
     * pool and the #of distinct named indices together govern the maximum
     * practical concurrency for unisolated writers.
     * <p>
     * Serialization of access to unisolated named indices is acomplished by
     * gaining an exclusive lock on the unisolated named index.
     */
    final protected WriteExecutorService writeService;

    /**
     * When <code>true</code> the {@link #sampleService} will be used run
     * {@link QueueStatisticsTask}s that collect statistics on the
     * {@link #readService}, {@link #writeService}, and the
     * {@link #txWriteService}.
     */
    final private boolean collectQueueStatistics;
    
    /**
     * Used to sample some counters at a once-per-second rate.
     */
    final private ScheduledExecutorService sampleService;
        
    /**
     * The timeout for {@link #shutdown()} -or- ZERO (0L) to wait for ever.
     */
    final long shutdownTimeout;

    /**
     * An object wrapping the properties specified to the ctor.
     */
    public Properties getProperties() {
        
        return new Properties(properties);
        
    }
    
    protected void assertOpen() {
        
        if (!open)
            throw new IllegalStateException();
        
    }
    
    public WriteExecutorService getWriteService() {
        
        assertOpen();
        
        return writeService;
        
    }
    
//    public LockManager<String> getLockManager() {
//        
//        assertOpen();
//        
//        return lockManager;
//        
//    }
    
    public ILocalTransactionManager getTransactionManager() {
        
        assertOpen();
        
        return transactionManager;
        
    }
    
    public IResourceManager getResourceManager() {
        
        assertOpen();
        
        return resourceManager;
        
    }

    public boolean isOpen() {
        
        return open;
        
    }
    
    /**
     * Shutdown the thread pools (running tasks will run to completion, but no
     * new tasks will start).
     */
    synchronized public void shutdown() {

        if(!isOpen()) return;

        open = false;
        
        if (INFO)
            log.info("begin");

        // time when shutdown begins.
        final long begin = System.currentTimeMillis();

        /*
         * Note: when the timeout is zero we approximate "forever" using
         * Long.MAX_VALUE.
         */

        final long shutdownTimeout = this.shutdownTimeout == 0L ? Long.MAX_VALUE
                : this.shutdownTimeout;
        
        final TimeUnit unit = TimeUnit.MILLISECONDS;
        
        txWriteService.shutdown();

        readService.shutdown();

        writeService.shutdown();

        if (sampleService != null)
            sampleService.shutdown();

        try {

            if (INFO) log.info("Awaiting transaction service termination");
            
            final long elapsed = System.currentTimeMillis() - begin;
            
            if(!txWriteService.awaitTermination(shutdownTimeout-elapsed, unit)) {
                
                log.warn("Transaction service termination: timeout");
                
            }

        } catch(InterruptedException ex) {
            
            log.warn("Interrupted awaiting transaction service termination.", ex);
            
        }

        try {

            if (INFO)
                log.info("Awaiting read service termination");

            final long elapsed = System.currentTimeMillis() - begin;
            
            if(!readService.awaitTermination(shutdownTimeout-elapsed, unit)) {
                
                log.warn("Read service termination: timeout");
                
            }

        } catch(InterruptedException ex) {
            
            log.warn("Interrupted awaiting read service termination.", ex);
            
        }

        try {

            final long elapsed = System.currentTimeMillis() - begin;
            
            final long timeout = shutdownTimeout-elapsed;

            if (INFO)
                log.info("Awaiting write service termination: will wait "
                        + timeout + "ms");

            if(!writeService.awaitTermination(timeout, unit)) {
                
                log.warn("Write service termination : timeout");
                
            }
            
        } catch(InterruptedException ex) {
            
            log.warn("Interrupted awaiting write service termination.", ex);
            
        }
    
        final long elapsed = System.currentTimeMillis() - begin;
        
        if (INFO)
            log.info("Done: elapsed=" + elapsed + "ms");
        
    }

    /**
     * Immediate shutdown (running tasks are cancelled rather than being
     * permitted to complete).
     * 
     * @see #shutdown()
     */
    public void shutdownNow() {

        if(!isOpen()) return;

        open = false;
        
        if (INFO)
            log.info("begin");
        
        final long begin = System.currentTimeMillis();
        
        txWriteService.shutdownNow();

        readService.shutdownNow();

        writeService.shutdownNow();

        if (sampleService != null)
            sampleService.shutdown();

        final long elapsed = System.currentTimeMillis() - begin;
        
        if (INFO)
            log.info("Done: elapsed=" + elapsed + "ms");

    }

    /**
     * (Re-)open a journal supporting concurrent operations.
     * 
     * @param properties
     *            See {@link ConcurrencyManager.Options}.
     * @param transactionManager
     *            The object managing the local transactions.
     * @param resourceManager
     *            The object managing the resources on which the indices are
     *            stored.
     */
    public ConcurrencyManager(final Properties properties,
            final ILocalTransactionManager transactionManager,
            final IResourceManager resourceManager) {

        if (properties == null)
            throw new IllegalArgumentException();

        if (transactionManager == null)
            throw new IllegalArgumentException();

        if (resourceManager == null)
            throw new IllegalArgumentException();

        this.properties = properties;
        
        this.transactionManager = transactionManager; 
         
        this.resourceManager = resourceManager;
        
        String val;

        final int txServicePoolSize;
        final int readServicePoolSize;

        // txServicePoolSize
        {

            val = properties.getProperty(ConcurrencyManager.Options.TX_SERVICE_CORE_POOL_SIZE,
                    ConcurrencyManager.Options.DEFAULT_TX_SERVICE_CORE_POOL_SIZE);

            txServicePoolSize = Integer.parseInt(val);

            if (txServicePoolSize < 0) {

                throw new RuntimeException("The '"
                        + ConcurrencyManager.Options.TX_SERVICE_CORE_POOL_SIZE
                        + "' must be non-negative.");

            }

            if (INFO)
                log.info(ConcurrencyManager.Options.TX_SERVICE_CORE_POOL_SIZE
                        + "=" + txServicePoolSize);

        }

        // readServicePoolSize
        {

            val = properties.getProperty(ConcurrencyManager.Options.READ_SERVICE_CORE_POOL_SIZE,
                    ConcurrencyManager.Options.DEFAULT_READ_SERVICE_CORE_POOL_SIZE);

            readServicePoolSize = Integer.parseInt(val);

            if (readServicePoolSize < 0) {

                throw new RuntimeException("The '"
                        + ConcurrencyManager.Options.READ_SERVICE_CORE_POOL_SIZE
                        + "' must be non-negative.");

            }

            if (INFO)
                log.info(ConcurrencyManager.Options.READ_SERVICE_CORE_POOL_SIZE
                        + "=" + readServicePoolSize);

        }

        // shutdownTimeout
        {

            val = properties.getProperty(ConcurrencyManager.Options.SHUTDOWN_TIMEOUT,
                    ConcurrencyManager.Options.DEFAULT_SHUTDOWN_TIMEOUT);

            shutdownTimeout = Long.parseLong(val);

            if (shutdownTimeout < 0) {

                throw new RuntimeException("The '" + ConcurrencyManager.Options.SHUTDOWN_TIMEOUT
                        + "' must be non-negative.");

            }

            if (INFO)
                log.info(ConcurrencyManager.Options.SHUTDOWN_TIMEOUT + "="
                        + shutdownTimeout);

        }

        // setup thread pool for concurrent transactions.
        if (txServicePoolSize == 0) {
            // cached thread pool.
            txWriteService = (ThreadPoolExecutor) Executors
                    .newCachedThreadPool(new DaemonThreadFactory
                            (getClass().getName()+".txWriteService"));
        } else {
            // fixed thread pool.
            txWriteService = (ThreadPoolExecutor) Executors.newFixedThreadPool(
                    txServicePoolSize, new DaemonThreadFactory
                    (getClass().getName()+".txWriteService"));
        }

        // setup thread pool for unisolated read operations.
        if (readServicePoolSize == 0) {
            // cached thread pool.
            readService = (ThreadPoolExecutor) Executors
                    .newCachedThreadPool(new DaemonThreadFactory
                            (getClass().getName()+".readService"));
        } else {
            // fixed thread pool.
            readService = (ThreadPoolExecutor) Executors.newFixedThreadPool(
                    readServicePoolSize, new DaemonThreadFactory
                    (getClass().getName()+".readService"));
        }

        // setup thread pool for unisolated write operations.
        {
            
            final int writeServiceCorePoolSize;
            final int writeServiceMaximumPoolSize;
            final int writeServiceQueueCapacity;
            final boolean writeServicePrestart;
            
            // writeServiceCorePoolSize
            {

                writeServiceCorePoolSize = Integer.parseInt(properties.getProperty(
                        ConcurrencyManager.Options.WRITE_SERVICE_CORE_POOL_SIZE,
                        ConcurrencyManager.Options.DEFAULT_WRITE_SERVICE_CORE_POOL_SIZE));

                if (writeServiceCorePoolSize < 0) {

                    throw new RuntimeException("The '"
                            + ConcurrencyManager.Options.WRITE_SERVICE_CORE_POOL_SIZE
                            + "' must be non-negative.");

                }

                if (INFO)
                    log.info(ConcurrencyManager.Options.WRITE_SERVICE_CORE_POOL_SIZE
                                    + "=" + writeServiceCorePoolSize);

            }

            // writeServiceMaximumPoolSize
            {

                writeServiceMaximumPoolSize = Integer.parseInt(properties.getProperty(
                        ConcurrencyManager.Options.WRITE_SERVICE_MAXIMUM_POOL_SIZE,
                        ConcurrencyManager.Options.DEFAULT_WRITE_SERVICE_MAXIMUM_POOL_SIZE));

                if (writeServiceMaximumPoolSize < writeServiceCorePoolSize) {

                    throw new RuntimeException("The '"
                            + ConcurrencyManager.Options.WRITE_SERVICE_MAXIMUM_POOL_SIZE
                            + "' must be greater than the core pool size.");

                }

                if (INFO)
                    log.info(ConcurrencyManager.Options.WRITE_SERVICE_MAXIMUM_POOL_SIZE
                                    + "=" + writeServiceMaximumPoolSize);

            }

            // writeServiceQueueCapacity
            {

                writeServiceQueueCapacity = Integer.parseInt(properties.getProperty(
                        ConcurrencyManager.Options.WRITE_SERVICE_QUEUE_CAPACITY,
                        ConcurrencyManager.Options.DEFAULT_WRITE_SERVICE_QUEUE_CAPACITY));

                if (writeServiceQueueCapacity < 0) {

                    throw new RuntimeException("The '"
                            + ConcurrencyManager.Options.WRITE_SERVICE_QUEUE_CAPACITY
                            + "' must be non-negative.");

                }

                if(INFO)
                    log.info(ConcurrencyManager.Options.WRITE_SERVICE_QUEUE_CAPACITY+ "="
                        + writeServiceQueueCapacity);

            }

            // writeServicePrestart
            {
                
                writeServicePrestart = Boolean.parseBoolean(properties.getProperty(
                        ConcurrencyManager.Options.WRITE_SERVICE_PRESTART_ALL_CORE_THREADS,
                        ConcurrencyManager.Options.DEFAULT_WRITE_SERVICE_PRESTART_ALL_CORE_THREADS));
                
                if (INFO)
                    log.info(ConcurrencyManager.Options.WRITE_SERVICE_PRESTART_ALL_CORE_THREADS
                                    + "=" + writeServicePrestart);

            }

            final long groupCommitTimeout = Long
                    .parseLong(properties
                            .getProperty(
                                    ConcurrencyManager.Options.WRITE_SERVICE_GROUP_COMMIT_TIMEOUT,
                                    ConcurrencyManager.Options.DEFAULT_WRITE_SERVICE_GROUP_COMMIT_TIMEOUT));

            if (INFO)
                log
                        .info(ConcurrencyManager.Options.WRITE_SERVICE_GROUP_COMMIT_TIMEOUT
                                + "=" + groupCommitTimeout);

            final long keepAliveTime = Long
                    .parseLong(properties
                            .getProperty(
                                    ConcurrencyManager.Options.WRITE_SERVICE_KEEP_ALIVE_TIME,
                                    ConcurrencyManager.Options.DEFAULT_WRITE_SERVICE_KEEP_ALIVE_TIME));

            if (INFO)
                log
                        .info(ConcurrencyManager.Options.WRITE_SERVICE_KEEP_ALIVE_TIME
                                + "=" + keepAliveTime);
            
            final BlockingQueue<Runnable> queue =
                ((writeServiceQueueCapacity == 0 || writeServiceQueueCapacity > 5000)
                        ? new LinkedBlockingQueue<Runnable>()
                        : new ArrayBlockingQueue<Runnable>(writeServiceQueueCapacity)
                        );
            
            writeService = new WriteExecutorService(//
                    resourceManager,//
                    writeServiceCorePoolSize,//
                    writeServiceMaximumPoolSize,//
                    keepAliveTime, TimeUnit.MILLISECONDS, // keepAliveTime
                    queue, //
                    new DaemonThreadFactory(getClass().getName()+".writeService"), //
                    groupCommitTimeout//
            );

            if (writeServicePrestart) {

                writeService.prestartAllCoreThreads();
                
            }
            
        }
        
        {

            collectQueueStatistics = Boolean
                    .parseBoolean(properties
                            .getProperty(
                                    IBigdataClient.Options.COLLECT_QUEUE_STATISTICS,
                                    IBigdataClient.Options.DEFAULT_COLLECT_QUEUE_STATISTICS));

            if (INFO)
                log.info(IBigdataClient.Options.COLLECT_QUEUE_STATISTICS + "="
                        + collectQueueStatistics);

        }
        
        if (collectQueueStatistics) {

            /*
             * Setup once-per-second sampling for some counters.
             */

            // @todo config.
            final double w = QueueStatisticsTask.DEFAULT_WEIGHT;
            final long initialDelay = 0; // initial delay in ms.
            final long delay = 1000; // delay in ms.
            final TimeUnit unit = TimeUnit.MILLISECONDS;
            
            writeServiceQueueStatisticsTask = new QueueStatisticsTask("writeService",
                    writeService, countersUN, w);

            txWriteServiceQueueStatisticsTask = new QueueStatisticsTask("txWriteService",
                    txWriteService, countersTX, w);

            readServiceQueueStatisticsTask = new QueueStatisticsTask("readService",
                    readService, countersHR, w);

            sampleService = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory
                    (getClass().getName()+".sampleService"));
                    
            sampleService.scheduleWithFixedDelay(writeServiceQueueStatisticsTask,
                    initialDelay, delay, unit);
            
            sampleService.scheduleWithFixedDelay(txWriteServiceQueueStatisticsTask,
                    initialDelay, delay, unit);

            sampleService.scheduleWithFixedDelay(readServiceQueueStatisticsTask,
                    initialDelay, delay, unit);

        } else {
            
            writeServiceQueueStatisticsTask = null;

            txWriteServiceQueueStatisticsTask = null;
            
            readServiceQueueStatisticsTask = null;
            
            sampleService = null;
            
        }
        
    }
    
    /** Counters for {@link #writeService}. */
    protected final TaskCounters countersUN  = new TaskCounters();
    
    /** Counters for the {@link #txWriteService}. */
    protected final TaskCounters countersTX = new TaskCounters();
    
    /** Counters for the {@link #readService}. */
    protected final TaskCounters countersHR = new TaskCounters();

    /**
     * Sampling instruments for the various queues giving us the moving average
     * of the queue length.
     */
    private final QueueStatisticsTask writeServiceQueueStatisticsTask;
    private final QueueStatisticsTask txWriteServiceQueueStatisticsTask;
    private final QueueStatisticsTask readServiceQueueStatisticsTask;
    
    /**
     * Interface defines and documents the counters and counter namespaces for
     * the {@link ConcurrencyManager}.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public static interface IConcurrencyManagerCounters {
       
        /**
         * The service to which historical read tasks are submitted.
         */
        String ReadService = "Read Service";

        /**
         * The service to which isolated write tasks are submitted.
         */
        String TXWriteService = "Transaction Write Service";
        
        /**
         * The service to which {@link ITx#UNISOLATED} tasks are submitted. This
         * is the service that handles commit processing. Tasks submitted to
         * this service are required to declare resource lock(s) and must
         * acquire those locks before they can begin executing.
         */
        String writeService = "Unisolated Write Service";

        /**
         * The {@link LockManager} that manages the resource locks for the
         * {@link #writeService}.
         */
        String WriteServiceLockManager = writeService + ICounterSet.pathSeparator + "LockManager";
        
    }
    
    /**
     * Reports the elapsed time since the service was started.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    private static class ServiceElapsedTimeInstrument extends Instrument<Long> {
     
        final long serviceStartTime;
        
        public ServiceElapsedTimeInstrument(final long serviceStartTime) {
        
            this.serviceStartTime = serviceStartTime;
            
        }
        
        public void sample() {
            
            setValue(System.currentTimeMillis() - serviceStartTime);
            
        }
        
    }
    
    /**
     * Return the {@link CounterSet}.
     */
    synchronized public CounterSet getCounters() {
        
        if (countersRoot == null){

            countersRoot = new CounterSet();

            // elapsed time since the service started (milliseconds).
            countersRoot.addCounter("elapsed",
                    new ServiceElapsedTimeInstrument(serviceStartTime));

            if (collectQueueStatistics) {

                // readService
                {
                    readServiceQueueStatisticsTask.addCounters(countersRoot
                            .makePath(IConcurrencyManagerCounters.ReadService));

                }

                // txWriteService
                {

                    txWriteServiceQueueStatisticsTask
                            .addCounters(countersRoot
                                    .makePath(IConcurrencyManagerCounters.TXWriteService));

                }

                // writeService
                {

                    writeServiceQueueStatisticsTask
                            .addCounters(countersRoot
                                    .makePath(IConcurrencyManagerCounters.writeService));

                    /*
                     * The lock manager for the write service.
                     */

                    countersRoot
                            .makePath(
                                    IConcurrencyManagerCounters.WriteServiceLockManager)
                            .attach(writeService.getLockManager().getCounters());

                }

            }

        }
        
        return countersRoot;
        
    }
    private CounterSet countersRoot;
    
    /**
     * Submit a task (asynchronous). Tasks will execute asynchronously in the
     * appropriate thread pool with as much concurrency as possible.
     * <p>
     * Note: Unisolated write tasks will NOT return before the next group commit
     * (exceptions may be thrown if the task fails or the commit fails). The
     * purpose of group commits is to provide higher throughput for writes on
     * the store by only syncing the data to disk periodically rather than after
     * every write. Group commits are scheduled by the {@link #commitService}.
     * The trigger conditions for group commits may be configured using
     * {@link ConcurrencyManager.Options}. If you are using the store in a
     * single threaded context then you may set
     * {@link Options#WRITE_SERVICE_CORE_POOL_SIZE} to ONE (1) which has the
     * effect of triggering commit immediately after each unisolated write.
     * However, note that you can not sync a disk more than ~ 30-40 times per
     * second so your throughput in write operations per second will never
     * exceed that for a single-threaded application writing on a hard disk.
     * (Your mileage can vary if you are writing on a transient store or using a
     * durable medium other than disk).
     * <p>
     * Note: The isolated indices used by a read-write transaction are NOT
     * thread-safe. Therefore a partial order is imposed over concurrent tasks
     * for the <strong>same</strong> transaction that seek to read or write on
     * the same index(s). Full concurrency is allowed when different
     * transactions access the same index(s), but write-write conflicts MAY be
     * detected during commit processing.
     * <p>
     * Note: The following exceptions MAY be wrapped by {@link Future#get()} for
     * tasks submitted via this method:
     * <dl>
     * <dt>{@link ValidationError}</dt>
     * <dd>An unisolated write task was attempting to commit the write set for
     * a transaction but validation failed. You may retry the entire
     * transaction.</dd>
     * <dt>{@link InterruptedException}</dt>
     * <dd>A task was interrupted during execution and before the task had
     * completed normally. You MAY retry the task, but note that this exception
     * is also generated when tasks are cancelled when the journal is being
     * {@link #shutdown()} after the timeout has expired or
     * {@link #shutdownNow()}. In either of these cases the task will not be
     * accepted by the journal.</dd>
     * <dt></dt>
     * <dd></dd>
     * </dl>
     * 
     * @param task
     *            The task.
     * 
     * @return The {@link Future} that may be used to resolve the outcome of the
     *         task.
     * 
     * @exception RejectedExecutionException
     *                if task cannot be scheduled for execution (typically the
     *                queue has a limited capacity and is full)
     * @exception NullPointerException
     *                if task null
     */
    public <T> Future<T> submit(final AbstractTask<T> task) {

        assertOpen();
        
        // Note that time the task was submitted for execution.
        task.nanoTime_submitTask = System.nanoTime();
        
        if( task.readOnly ) {

            /*
             * Reads against historical data do not require concurrency control.
             * 
             * The only distinction between a transaction and an unisolated read
             * task is the choice of the historical state from which the task
             * will read. A ReadOnly transaction reads from the state of the
             * index as of the start time of the transaction. A ReadCommitted
             * transaction and an unisolated reader both read from the last
             * committed state of the index.
             */

            if (INFO)
                log.info("Submitted to the read service: "
                        + task.getClass().getName() + ", timestamp="
                        + task.timestamp);

            return submitWithDynamicLatency(task, readService, countersHR);

        } else {

            if (task.isReadWriteTx) {

                /*
                 * A task that reads from historical data and writes on isolated
                 * indices backed by a temporary store. Concurrency control is
                 * required for the isolated indices on the temporary store, but
                 * not for the reads against the historical data.
                 */

                if (INFO)
                    log.info("Submitted to the transaction service: "
                            + task.getClass().getName() + ", timestamp="
                            + task.timestamp);

                return submitWithDynamicLatency(task, txWriteService, countersTX);

            } else {

                /*
                 * A task that reads from and writes on "live" indices. The live
                 * indices are NOT thread-safe. Concurrency control provides a
                 * partial order over the executing tasks such that there is
                 * never more than one task with access to a given live index.
                 */

                if (INFO)
                    log.info("Submitted to the write service: "
                            + task.getClass().getName() + ", timestamp="
                            + task.timestamp);

                return submitWithDynamicLatency(task, writeService, countersUN);

            }

        }
        
    }
    
    /**
     * Submit a task to a service, dynamically imposing latency on the caller
     * based on the #of tasks already in the queue for that service.
     * 
     * @param task
     *            The task.
     * @param service
     *            The service.
     * 
     * @return The {@link Future}.
     */
    private <T> Future<T> submitWithDynamicLatency(
            final AbstractTask<T> task, final ExecutorService service,
            final TaskCounters taskCounters) {

        taskCounters.taskSubmitCount.incrementAndGet();
        
        /*
         * Note: The StoreManager (part of the ResourceManager) has some
         * asynchronous startup processing where it scans the existing store
         * files or creates the initial store file. This code will await the
         * completion of startup processing before permitting a task to be
         * submittinged. This causes clients to block until we are ready to
         * process their tasks.
         */
        if (resourceManager instanceof StoreManager) {
            
            if (!((StoreManager) resourceManager).awaitRunning()) {

                throw new RejectedExecutionException(
                        "StoreManager is not available");

            }
            
        }

        if(backoff && service instanceof ThreadPoolExecutor) {

            final BlockingQueue<Runnable> queue = ((ThreadPoolExecutor) service)
                    .getQueue();
        
            if (!(queue instanceof SynchronousQueue)) {

                /*
                 * Note: SynchronousQueue is used when there is no limit on the
                 * #of workers, e.g., when using
                 * Executors.newCachedThreadPool(). The SynchronousQueue has a
                 * ZERO capacity. Therefore the logic to test the remaining
                 * capacity and inject a delay simply does not work for this
                 * type of queue.
                 */
                
                final int queueRemainingCapacity = queue.remainingCapacity();

                final int queueSize = queue.size();

                if (queue.size() * 1.10 >= queueRemainingCapacity) {

                    try {

                        /*
                         * Note: Any delay here what so ever causes the #of
                         * tasks in a commit group to be governed primarily by
                         * the CORE pool size.
                         */

                        if (INFO)
                            System.err.print("z");

                        Thread.sleep(50/* ms */);

                    } catch (InterruptedException e) {

                        throw new RuntimeException(e);

                    }

                }

            }
            
        }

        return service.submit(task);

    }
    /**
     * When <code>true</code> imposes dynamic latency on ariving tasks in
     * {@link #submitWithDynamicLatency(AbstractTask, ExecutorService, TaskCounters)}.
     * 
     * @todo revisit the question of imposed latency here based on performance
     *       analysis (of queue length vs response time) for the federation
     *       under a variety of workloads (tasks such as rdf data load, rdf data
     *       query, bigdata repository workloads, etc.).
     *       <p>
     *       Note that {@link Executors#newCachedThreadPool()} uses a
     *       {@link SynchronousQueue} and that queue has ZERO capacity.
     */
    static private final boolean backoff = false;

    /**
     * Executes the given tasks, returning a list of Futures holding their
     * status and results when all complete. Note that a completed task could
     * have terminated either normally or by throwing an exception. The results
     * of this method are undefined if the given collection is modified while
     * this operation is in progress.
     * <p>
     * Note: Contract is per {@link ExecutorService#invokeAll(Collection)}
     * 
     * @param tasks
     *            The tasks.
     * 
     * @return Their {@link Future}s.
     * 
     * @exception InterruptedException
     *                if interrupted while waiting, in which case unfinished
     *                tasks are cancelled.
     * @exception NullPointerException
     *                if tasks or any of its elements are null
     * @exception RejectedExecutionException
     *                if any task cannot be scheduled for execution
     */
    public <T> List<Future<T>> invokeAll(
            final Collection<? extends AbstractTask<T>> tasks)
            throws InterruptedException {

        assertOpen();

        final List<Future<T>> futures = new LinkedList<Future<T>>();

        boolean done = false;

        try {

            // submit all.
            
            for (AbstractTask<T> task : tasks) {

                futures.add(submit(task));

            }

            // await all futures.
            
            for (Future<? extends Object> f : futures) {

                if (!f.isDone()) {

                    try {

                        f.get();

                    } catch (ExecutionException ex) {

                        // ignore.
                        
                    } catch (CancellationException ex) {

                        // ignore.

                    }

                }
                
            }

            done = true;
            
            return futures;
            
        } finally {
            
            if (!done) {

                // At least one future did not complete.
                
                for (Future<T> f : futures) {

                    if(!f.isDone()) {

                        f.cancel(true/* mayInterruptIfRunning */);
                        
                    }
                    
                }
                
            }
        
        }

    }
    
    /**
     * Executes the given tasks, returning a list of Futures holding their
     * status and results when all complete or the timeout expires, whichever
     * happens first. Note that a completed task could have terminated either
     * normally or by throwing an exception. The results of this method are
     * undefined if the given collection is modified while this operation is in
     * progress.
     * <p>
     * Note: Contract is based on
     * {@link ExecutorService#invokeAll(Collection, long, TimeUnit)} but only
     * the {@link Future}s of the submitted tasks are returned.
     * 
     * @param tasks
     *            The tasks.
     * 
     * @return The {@link Future}s of all tasks that were
     *         {@link #submit(AbstractTask) submitted} prior to the expiration
     *         of the timeout.
     * 
     * @exception InterruptedException
     *                if interrupted while waiting, in which case unfinished
     *                tasks are cancelled.
     * @exception NullPointerException
     *                if tasks or any of its elements are null
     * @exception RejectedExecutionException
     *                if any task cannot be scheduled for execution
     */
    public List<Future> invokeAll(
            final Collection<? extends AbstractTask> tasks, final long timeout,
            final TimeUnit unit) throws InterruptedException {

        assertOpen();
        
        final List<Future> futures = new LinkedList<Future>();

        boolean done = false;
        
        long nanos = unit.toNanos(timeout);
        
        long lastTime = System.nanoTime();
        
        try {

            // submit all.
            
            for (AbstractTask task : tasks) {

                long now = System.nanoTime();
                
                nanos -= now - lastTime;
                
                lastTime = now;
                
                if (nanos <= 0) {

                    // timeout.
                    
                    return futures;
                    
                }
                
                futures.add(submit(task));

            }

            // await all futures.
            
            for (Future f : futures) {

                if (!f.isDone()) {

                    if (nanos <= 0) { 
                     
                        // timeout
                        
                        return futures;
                        
                    }
                    
                    try {

                        f.get(nanos, TimeUnit.NANOSECONDS);

                    } catch (TimeoutException ex) {

                        return futures;

                    } catch (ExecutionException ex) {

                        // ignore.

                    } catch (CancellationException ex) {

                        // ignore.

                    }

                    long now = System.nanoTime();
                    
                    nanos -= now - lastTime;
                    
                    lastTime = now;

                }

            }

            done = true;

            return futures;

        } finally {

            if (!done) {

                // At least one future did not complete.

                for (Future f : futures) {

                    if (!f.isDone()) {

                        f.cancel(true/* mayInterruptIfRunning */);

                    }

                }
                
            }
        
        }
        
    }

}
