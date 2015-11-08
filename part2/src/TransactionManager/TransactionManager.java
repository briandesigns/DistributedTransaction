package TransactionManager;

import LockManager.*;
import LockManager.LockManager;
import ResourceManager.TCPServer;
import ResourceManager.ResourceManager;
import ResourceManager.Customer;
import ResourceManager.RMHashtable;

import ResourceManager.MiddlewareRunnable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Vector;

/**
 * PLAN
 * TM keeps a counter and a boolean activeTransaction
 * middleware's client interface once receives start(), actives TM counter and turn activeTransaction to true
 * while the activeTransaction is still true, route all client calls to TM.op()
 * if mw does not receive a client call before tm.counter expires, then TM abort and turn activeTransaction to false
 * before any client add() is executed, TM first read that data and save its location, count, price
 * then perform the add()
 * if abort, add(location, -count, price) (this command can be saved into a table of things that need to be executed on abort())
 * <p/>
 * before any client delete() is executed, TM first query(), queryPrice(), queryReserved()(not implemented), and save location, count, price, reserved
 * then perform delete(), which deletes also customer reservations as well?
 * if abort, add(location, count, price, reserved), add back customer reservations on those items? (saved into a table of things that need to be executed on abort())
 * <p/>
 * before any client reserve() is executed, TM first query(), queryPrice(), queryReserved(), and save ito location, count, price, reserved
 * then  perform reserve()
 * if abort, add(location, +1, price, -1), delete client reservedItem (save this into a  table of things that need to be executed on abort())
 * <p/>
 * itinerary should be just implemented as a transaction itself
 * <p/>
 * the list of things that needs to be executed on abort() should just be a list of commands to feed to run() of middleware
 */
public class TransactionManager implements ResourceManager {
    public static RMHashtable transactionTable;
    private ArrayList<String> undoStack;
    private int currentActiveTransactionID;
    public static final int UNUSED_TRANSACTION_ID = -1;
    private MiddlewareRunnable myMWRunnable;
    private boolean inTransaction = false;
    private Thread TTLCountDownThread;
    private static final int TTL_MS = 20000;
    public static final String CAR = "car";
    public static final String FLIGHT = "flight";
    public static final String ROOM = "room";
    public static final String CUSTOMER = "customer";

    {
        transactionTable = new RMHashtable();
    }

    private void setInTransaction(boolean decision) {
        this.inTransaction = decision;
    }

    public boolean isInTransaction() {
        return inTransaction;
    }

    public void renewTTLCountDown() {
        TTLCountDownThread.interrupt();
        startTTLCountDown();
    }

    public void stopTTLCountDown() {
        TTLCountDownThread.interrupt();
    }

    private void startTTLCountDown() {
        TTLCountDownThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.currentThread().sleep(TTL_MS);
                    abort();
                } catch (InterruptedException e) {
                    System.out.println("TTL renewed");
                }
            }
        });
        TTLCountDownThread.start();
    }


    public TransactionManager(MiddlewareRunnable myMWRunnable) {
        this.myMWRunnable = myMWRunnable;
        this.undoStack = new ArrayList<String>();
    }

    private boolean undoAll() {
        boolean result = true;
        for (int i = this.undoStack.size() - 1; i >= 0; i--) {
            String line = undoStack.get(i);
            if (line.toLowerCase().contains("flight")) {
                myMWRunnable.toFlight.println(line);
                try {
                    if (myMWRunnable.fromFlight.readLine().toLowerCase().contains("false"))
                        result = false;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (line.toLowerCase().contains("car")) {
                myMWRunnable.toCar.println(line);
                try {
                    if (myMWRunnable.fromCar.readLine().toLowerCase().contains("false"))
                        result = false;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (line.toLowerCase().contains("room")) {
                myMWRunnable.toRoom.println(line);
                try {
                    if (myMWRunnable.fromRoom.readLine().toLowerCase().contains("false"))
                        result = false;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (line.toLowerCase().contains("customer")) {
                String[] cmdWords = line.split(",");
                int choice = myMWRunnable.findChoice(cmdWords);
                switch (choice) {
                    case 9:
                        //todo: check if this is correct
                        result = myMWRunnable.deleteCustomer(Integer.parseInt(cmdWords[1]), Integer.parseInt(cmdWords[2]));
                        break;
                    case 22:
                        //todo: check if this is correct
                        result = myMWRunnable.newCustomerId(Integer.parseInt(cmdWords[1]), Integer.parseInt(cmdWords[2]));
                        break;
                }
            } else
                ;
        }
        return result;
    }

    public boolean start() {
        if (!isInTransaction()) {
            startTTLCountDown();
            setInTransaction(true);
            System.out.println("transaction started");
            return true;
        } else {
            System.out.println("nothing to start, already in transaction");
            return false;
        }

    }

    public boolean abort() {
        stopTTLCountDown();
        setInTransaction(false);
        boolean result = undoAll();
        transactionTable.remove(this.currentActiveTransactionID);
        TCPServer.lm.UnlockAll(currentActiveTransactionID);
        currentActiveTransactionID = UNUSED_TRANSACTION_ID;
        this.undoStack = new ArrayList<String>();
        System.out.println("abort() call ended and returns: " + result);
        return result;
    }

    public boolean commit() {
        stopTTLCountDown();
        setInTransaction(false);
        transactionTable.remove(this.currentActiveTransactionID);
        TCPServer.lm.UnlockAll(currentActiveTransactionID);
        currentActiveTransactionID = UNUSED_TRANSACTION_ID;
        this.undoStack = new ArrayList<String>();
        return true;
    }

    @Override
    public boolean addFlight(int id, int flightNumber, int numSeats, int flightPrice) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, FLIGHT, LockManager.WRITE)) {
                return false;
            }
            this.currentActiveTransactionID = id;
            String undoCmd;
            if (myMWRunnable.isExistingFlight(id, flightNumber)) {
                undoCmd = "undoAddFlight," + id + "," + flightNumber + "," + myMWRunnable.queryFlight(id, flightNumber) + "," + myMWRunnable.queryFlightPrice(id, flightNumber) + "," + myMWRunnable.queryFlightReserved(id, flightNumber);
                System.out.println("just built undoCmd:" + undoCmd);
            } else {
                undoCmd = "deleteFlight," + id + "," + flightNumber;
            }
            undoStack.add(undoCmd);

            boolean[] involvedRMs = (boolean[]) transactionTable.get(id);
            if (involvedRMs == null) {
                involvedRMs = new boolean[]{true, false, false, false};
                transactionTable.put(id, involvedRMs);
            } else {
                involvedRMs[0] = true;
            }
            return myMWRunnable.addFlight(id, flightNumber, numSeats, flightPrice);
        } catch (DeadlockException e) {
//            e.printStackTrace();
            System.out.println("TM ADDFLIGHT DEADLOCK JUST ABORTED, ABOUT TO RETURN FALSE");
            abort();
            return false;
        }
    }


    @Override
    public boolean deleteFlight(int id, int flightNumber) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, FLIGHT, LockManager.WRITE)) {
                return false;
            }
            this.currentActiveTransactionID = id;
            if (myMWRunnable.isExistingFlight(id, flightNumber)) {
                String undoCmd = "undoAddFlight," + id + "," + flightNumber + "," + myMWRunnable.queryFlight(id, flightNumber) + "," + myMWRunnable.queryFlightPrice(id, flightNumber) + "," + myMWRunnable.queryFlightReserved(id, flightNumber);
                undoStack.add(undoCmd);
            }
            boolean[] involvedRMs = (boolean[]) transactionTable.get(id);
            if (involvedRMs == null) {
                involvedRMs = new boolean[]{true, false, false, false};
                transactionTable.put(id, involvedRMs);
            } else {
                involvedRMs[0] = true;
            }
            return myMWRunnable.deleteFlight(id, flightNumber);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return false;
        }
    }

    @Override
    public int queryFlight(int id, int flightNumber) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, FLIGHT, LockManager.READ)) {
                return -2;
            }
            this.currentActiveTransactionID = id;
            return myMWRunnable.queryFlight(id, flightNumber);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return -2;
        }
    }

    @Override
    public int queryFlightPrice(int id, int flightNumber) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, FLIGHT, LockManager.READ)) {
                return -2;
            }
            this.currentActiveTransactionID = id;
            return myMWRunnable.queryFlightPrice(id, flightNumber);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return -2;
        }
    }

    @Override
    public boolean addCars(int id, String location, int numCars, int carPrice) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, CAR, LockManager.WRITE)) {
                return false;
            }
            this.currentActiveTransactionID = id;
            String undoCmd;
            if (myMWRunnable.isExistingCars(id, location)) {
                undoCmd = "undoAddCars," + id + "," + location + "," + myMWRunnable.queryCars(id, location) + "," + myMWRunnable.queryCarsPrice(id, location) + "," + myMWRunnable.queryCarsReserved(id, location);
                System.out.println("just built undoCmd:" + undoCmd);
            } else {
                undoCmd = "deleteCars," + id + "," + location;
            }
            undoStack.add(undoCmd);

            boolean[] involvedRMs = (boolean[]) transactionTable.get(id);
            if (involvedRMs == null) {
                involvedRMs = new boolean[]{false, true, false, false};
                transactionTable.put(id, involvedRMs);
            } else {
                involvedRMs[1] = true;
            }
            return myMWRunnable.addCars(id, location, numCars, carPrice);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return false;
        }
    }

    @Override
    public boolean deleteCars(int id, String location) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, CAR, LockManager.WRITE)) {
                return false;
            }
            this.currentActiveTransactionID = id;
            if (myMWRunnable.isExistingCars(id, location)) {
                String undoCmd = "undoAddCars," + id + "," + location + "," + myMWRunnable.queryCars(id, location) + "," + myMWRunnable.queryCarsPrice(id, location) + "," + myMWRunnable.queryCarsReserved(id, location);
                undoStack.add(undoCmd);
            }
            boolean[] involvedRMs = (boolean[]) transactionTable.get(id);
            if (involvedRMs == null) {
                involvedRMs = new boolean[]{false, true, false, false};
                transactionTable.put(id, involvedRMs);
            } else {
                involvedRMs[1] = true;
            }
            return myMWRunnable.deleteCars(id, location);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return false;
        }
    }

    @Override
    public int queryCars(int id, String location) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, CAR, LockManager.READ)) {
                return -2;
            }
            this.currentActiveTransactionID = id;
            return myMWRunnable.queryCars(id, location);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return -1;
        }
    }

    @Override
    public int queryCarsPrice(int id, String location) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, CAR, LockManager.READ)) {
                return -2;
            }
            this.currentActiveTransactionID = id;
            return myMWRunnable.queryCarsPrice(id, location);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return -2;
        }
    }

    @Override
    public boolean addRooms(int id, String location, int numRooms, int roomPrice) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, ROOM, LockManager.WRITE)) {
                return false;
            }
            this.currentActiveTransactionID = id;
            String undoCmd;
            if (myMWRunnable.isExistingRooms(id, location)) {
                undoCmd = "undoAddRooms," + id + "," + location + "," + myMWRunnable.queryRooms(id, location) + "," + myMWRunnable.queryRoomsPrice(id, location) + "," + myMWRunnable.queryRoomsReserved(id, location);
                System.out.println("just built undoCmd:" + undoCmd);
            } else {
                undoCmd = "deleteRooms," + id + "," + location;
            }
            undoStack.add(undoCmd);
            boolean[] involvedRMs = (boolean[]) transactionTable.get(id);
            if (involvedRMs == null) {
                involvedRMs = new boolean[]{false, false, true, false};
                transactionTable.put(id, involvedRMs);
            } else {
                involvedRMs[2] = true;
            }
            return myMWRunnable.addRooms(id, location, numRooms, roomPrice);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return false;
        }
    }

    @Override
    public boolean deleteRooms(int id, String location) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, ROOM, LockManager.WRITE)) {
                return false;
            }
            this.currentActiveTransactionID = id;
            if (myMWRunnable.isExistingRooms(id, location)) {
                String undoCmd = "undoAddRooms," + id + "," + location + "," + myMWRunnable.queryRooms(id, location) + "," + myMWRunnable.queryRoomsPrice(id, location) + "," + myMWRunnable.queryRoomsReserved(id, location);
                undoStack.add(undoCmd);
            }
            boolean[] involvedRMs = (boolean[]) transactionTable.get(id);
            if (involvedRMs == null) {
                involvedRMs = new boolean[]{false, false, true, false};
                transactionTable.put(id, involvedRMs);
            } else {
                involvedRMs[2] = true;
            }
            return myMWRunnable.deleteRooms(id, location);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return false;
        }
    }

    @Override
    public int queryRooms(int id, String location) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, ROOM, LockManager.READ)) {
                return -2;
            }
            this.currentActiveTransactionID = id;
            return myMWRunnable.queryRooms(id, location);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return -2;
        }
    }

    @Override
    public int queryRoomsPrice(int id, String location) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, ROOM, LockManager.READ)) {
                return -2;
            }
            this.currentActiveTransactionID = id;
            return myMWRunnable.queryRoomsPrice(id, location);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return -2;
        }
    }

    @Override
    public int newCustomer(int id) {
        try {
            renewTTLCountDown();
            if (!(TCPServer.lm.Lock(id, CUSTOMER, LockManager.WRITE))) {
                return -2;
            }
            this.currentActiveTransactionID = id;
            int value = myMWRunnable.newCustomer(id);

            String undoCmd = "deleteCustomer," + id + "," + value;
            System.out.println("just built undoCmd:" + undoCmd);
            undoStack.add(undoCmd);

            boolean[] involvedRMs = (boolean[]) transactionTable.get(id);
            if (involvedRMs == null) {
                involvedRMs = new boolean[]{false, false, false, true};
                transactionTable.put(id, involvedRMs);
            } else {
                involvedRMs[3] = true;
            }
            return value;
        } catch (DeadlockException e) {
//            e.printStackTrace();
            abort();
            return -2;
        }
    }

    @Override
    public boolean newCustomerId(int id, int customerId) {
        try {
            renewTTLCountDown();
            if (!(TCPServer.lm.Lock(id, CUSTOMER, LockManager.WRITE))) {
                return false;
            }
            this.currentActiveTransactionID = id;
            boolean success = myMWRunnable.newCustomerId(id, customerId);
            if (success) {
                String undoCmd = "deleteCustomer," + id + "," + customerId;
                System.out.println("just built undoCmd:" + undoCmd);
                undoStack.add(undoCmd);
            }
            boolean[] involvedRMs = (boolean[]) transactionTable.get(id);
            if (involvedRMs == null) {
                involvedRMs = new boolean[]{false, false, false, true};
                transactionTable.put(id, involvedRMs);
            } else {
                involvedRMs[3] = true;
            }
            return success;
        } catch (DeadlockException e) {
//            e.printStackTrace();
            abort();
            return false;
        }
    }

    @Override
    public boolean deleteCustomer(int id, int customerId) {
        try {
            if (!(TCPServer.lm.Lock(id, CUSTOMER, LockManager.WRITE) && (TCPServer.lm.Lock(id, CAR, LockManager.WRITE) && TCPServer.lm.Lock(id, FLIGHT, LockManager.WRITE) && TCPServer.lm.Lock(id, ROOM, LockManager.WRITE)))) {
                return false;
            }
            this.currentActiveTransactionID = id;
            Customer cust = ((Customer) myMWRunnable.readData(id, Customer.getKey(customerId)));
            //todo: store the customer into an arraylist so that it could be readded if abort
            //todo: in undo routine, add back the customer, iterate through customer reservations and increment reserved and decrement count
        } catch (DeadlockException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public String queryCustomerInfo(int id, int customerId) {
        return null;
    }

    @Override
    public boolean reserveFlight(int id, int customerId, int flightNumber) {
        return false;
        //todo: save customer id into an arraylist so that it can be refound when aborting
        //todo: in undo routine, find the customer,call unreserveflight(customerId,flightNumber)
        //todo: unreserveflight(customerId,flightNumber): adjust count and reserved for flight, and calls customer.unreserve()
    }

    @Override
    public boolean reserveCar(int id, int customerId, String location) {
        return false;
        //todo: check reserveflight
    }

    @Override
    public boolean reserveRoom(int id, int customerId, String location) {
        return false;
        //todo: check reserveFlight
    }

    @Override
    public boolean reserveItinerary(int id, int customerId, Vector flightNumbers, String location, boolean car, boolean room) {
        return false;
        //todo: need to figure how to do this
    }

    @Override
    public boolean increaseReservableItemCount(int id, String key, int count) {
        return false;
        //todo: check how the middlewareversion works first, then check hwo to implement this
    }
}