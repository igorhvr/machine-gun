/*
 * Copyright (c) 2010.
 * CC-by Felipe Micaroni Lalli
 */

package br.eti.fml.implementations;

import br.eti.fml.machinegun.auditorship.ArmyAudit;
import br.eti.fml.machinegun.externaltools.Consumer;
import br.eti.fml.machinegun.externaltools.PersistedQueueManager;
import com.google.protobuf.InvalidProtocolBufferException;
import kyotocabinet.DB;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

public class KyotoCabinetBasedPersistedQueue implements PersistedQueueManager {
    private Map<String, DB> db = new HashMap<String, DB>();   
    private ArrayList<Thread> threads = new ArrayList<Thread>();
    private long size = 0;
    private boolean closed = false;
    private Semaphore semaphore = new Semaphore(1);

    public KyotoCabinetBasedPersistedQueue(File directory, String ... queues)
            throws IOException {

        directory.mkdirs();

        if (!directory.isDirectory()) {
            throw new IllegalArgumentException(directory + " must be a directory!");
        }

        for (String queue : queues) {
            try {
                DB db = new DB();
                this.db.put(queue, db);

                if (!db.open(directory.getAbsolutePath()
                        + File.separatorChar + "queue-"
                        + Integer.toHexString(queue.hashCode()) + ".kch#tune_defrag=10000",
                            DB.OWRITER | DB.OCREATE)) {

                    throw db.error();
                }

                db.tune_encoding("UTF-8");

                db.cas(head(queue), null, longToBytes(0L));
                db.cas(tail(queue), null, longToBytes(-1L));

                System.out.println("status: " + db.status());

                //System.out.println("(" + bytesToLong(db.get(head(queue))) + ";"
                //        + bytesToLong(db.get(tail(queue))) + ")");

            } catch (UnsatisfiedLinkError e) {
                throw new IOException("java.library.path=\""
                        + System.getProperty("java.library.path") + "\"", e);
            }
        }
    }

    private byte[] head(String queue) {
        return stringToBytes(queue + ".HEAD");        
    }

    private byte[] tail(String queue) {
        return stringToBytes(queue + ".TAIL");        
    }

    private byte[] stringToBytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] longToBytes(Long value) {
        if (value == null) {
            return null;
        } else {
            Primitives.LongType.Builder data
                    = Primitives.LongType.newBuilder();
            
            data.setValue(value);
            Primitives.LongType bytes = data.build();
            return bytes.toByteArray();            
        }
    }

    public Long bytesToLong(byte[] bytes) {
        if (bytes == null) {
            return null;
        } else {
            try {
                Primitives.LongType data
                        = Primitives.LongType.parseFrom(bytes);
    
                return data.getValue();
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void putIntoAnEmbeddedQueue(
            ArmyAudit armyAudit, String queueName, byte[] data)
                throws InterruptedException {

        DB db = this.db.get(queueName);
        semaphore.acquire();

        boolean ok = false;

        while (!ok) {
            long tail = bytesToLong(db.get(tail(queueName)));
            long newTail = tail + 1L;
            ok = db.cas(tail(queueName),
                    longToBytes(tail), longToBytes(newTail));

            if (!ok) {
                System.out.print(".");
            } else {
                db.set(stringToBytes("addr." + newTail), data);
            }
        }

        semaphore.release();
    }

    @Override
    public void registerANewConsumerInAnEmbeddedQueue(
            final ArmyAudit armyAudit, final String queueName,
            final Consumer consumer) {

        size++;

        final DB db = KyotoCabinetBasedPersistedQueue.this.db.get(queueName);        

        AtomicReference<Thread> t = new AtomicReference<Thread>(
                new Thread("consumer " + size + " of " + size) {

            public void run() {
                while (!closed) {
                    try {
                        semaphore.acquire();
                        long headToBeProcessed = bytesToLong(
                                db.get(head(queueName)));

                        long tail = bytesToLong(db.get(tail(queueName)));
                        boolean needProcess = false;

                        if (tail >= headToBeProcessed) {
                            needProcess = db.cas(head(queueName),
                                    longToBytes(headToBeProcessed),
                                    longToBytes(headToBeProcessed + 1L));
                        }

                        semaphore.release();

                        if (!needProcess) {
                           Thread.sleep(300);
                        } else {
                            byte[] addr = stringToBytes(
                                    "addr." + headToBeProcessed);

                            byte[] data = db.get(addr);

                            if (data != null) {
                                consumer.consume(data);
                                db.remove(addr);
                            }
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                armyAudit.consumerHasBeenStopped(Thread.currentThread().getName());
            }
        });

        threads.add(t.get());
        t.get().start();
    }

    public synchronized void close() throws InterruptedException {
        if (!closed) {
            closed = true;

            for (Thread t : threads) {
                t.join();
            }

            for (DB db : this.db.values()) {
                if (!db.close()) {
                    db.error().printStackTrace();
                }
            }
        }
    }

    public void finalize() {
        try {
            close();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public boolean isEmpty(String queueName) {
        if (closed) {
            throw new IllegalStateException("Kyoto already closed!");
        } else {
            return bytesToLong(this.db.get(queueName).get(tail(queueName)))
                    < bytesToLong(this.db.get(queueName).get(head(queueName)));
        }
    }

    @Override
    public void killAllConsumers(String queueName) throws InterruptedException {
        close();
    }
}
