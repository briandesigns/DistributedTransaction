package TransactionManager;

import ResourceManager.*;
import LockManager.*;
import LockManager.LockManager;
import ResourceManager.TCPServer;
import ResourceManager.ResourceManager;
import ResourceManager.Customer;
import ResourceManager.RMHashtable;
import ResourceManager.MiddlewareRunnable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Vector;
//todo: shutdowns
public class TransactionManager implements ResourceManager {
    public static RMHashtable transactionTable;
    public ArrayList<Customer> customers;
    private ArrayList<String> undoStack;
    private static int uniqueTransactionID = 0;
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

    public int getCurrentActiveTransactionID() {
        return currentActiveTransactionID;
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
        this.customers = new ArrayList<Customer>();
    }

    private static synchronized int generateUniqueXID() {
        uniqueTransactionID++;
        return uniqueTransactionID;
    }

    private boolean undoAll() {
        boolean result = true;
        for (int i = this.undoStack.size() - 1; i >= 0; i--) {
            String line = undoStack.get(i);
            if (line.toLowerCase().contains("unreserve")) {
                String[] cmdWords = line.split(",");
                try {
                    result = myMWRunnable.unreserveItem(Integer.parseInt(cmdWords[1]), Integer.parseInt(cmdWords[2]), (cmdWords[3]), (cmdWords[4]));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (line.toLowerCase().contains("flight")) {
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
            } else if (line.toLowerCase().contains("undodeletecustomer")) {
                String[] cmdWords = line.split(",");
                result = myMWRunnable.undoDeleteCustomer(Integer.parseInt(cmdWords[1]), Integer.parseInt(cmdWords[2]));
            } else if (line.toLowerCase().contains("deletecustomer")) {
                String[] cmdWords = line.split(",");
                result = myMWRunnable.deleteCustomer(Integer.parseInt(cmdWords[1]), Integer.parseInt(cmdWords[2]));

            } else
                ;

        }
        return result;
    }

    public boolean start() {
        if (!isInTransaction()) {
            startTTLCountDown();
            setInTransaction(true);
            currentActiveTransactionID = generateUniqueXID();
            System.out.println("transaction started with XID: " + currentActiveTransactionID);
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
        currentActiveTransactionID = UNUSED_TRANSACTION_ID;
        this.undoStack = new ArrayList<String>();
        this.customers = new ArrayList<Customer>();
        TCPServer.lm.UnlockAll(currentActiveTransactionID);
        System.out.println("abort() call ended and undoAll() successful:" + result);
        return result;
    }

    public boolean commit() {
        stopTTLCountDown();
        setInTransaction(false);
        transactionTable.remove(this.currentActiveTransactionID);
        currentActiveTransactionID = UNUSED_TRANSACTION_ID;
        this.undoStack = new ArrayList<String>();
        this.customers = new ArrayList<Customer>();
        TCPServer.lm.UnlockAll(currentActiveTransactionID);
        return true;
    }

    @Override
    public boolean addFlight(int id, int flightNumber, int numSeats, int flightPrice) {
        try {
            renewTTLCountDown();
            if (!TCPServer.lm.Lock(id, FLIGHT, LockManager.WRITE)) {
                return false;
            }
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }

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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return -3;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return -3;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return -3;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return -3;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return -3;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return -3;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return -3;
            }
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
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }
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
            renewTTLCountDown();
            if (!(TCPServer.lm.Lock(id, CUSTOMER, LockManager.WRITE) && (TCPServer.lm.Lock(id, CAR, LockManager.WRITE) && TCPServer.lm.Lock(id, FLIGHT, LockManager.WRITE) && TCPServer.lm.Lock(id, ROOM, LockManager.WRITE)))) {
                return false;
            }
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }
            Customer cust = ((Customer) myMWRunnable.readData(id, Customer.getKey(customerId)));
            if (cust == null) {
                System.out.println("customer does not exist. failed to delete customer");
                return false;
            }
            customers.add(cust);
            String cmdWords = "undoDeleteCustomer" + "," + id + "," + customerId;
            undoStack.add(cmdWords);
            return myMWRunnable.deleteCustomer(id, customerId);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return false;
        }
    }


    @Override
    public String queryCustomerInfo(int id, int customerId) {
        try {
            renewTTLCountDown();
            if (!(TCPServer.lm.Lock(id, CUSTOMER, customerId))) {
                return "can't get customer Info";
            }
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return "transaction ID does not match current transaction, command ignored";
            }
            return myMWRunnable.queryCustomerInfo(id, customerId);
        } catch (DeadlockException e) {
            e.printStackTrace();
            abort();
            return "can't get customer Info";
        }
    }

    @Override
    public boolean reserveFlight(int id, int customerId, int flightNumber) {
        try {
            renewTTLCountDown();
            if (!(TCPServer.lm.Lock(id, CUSTOMER, LockManager.WRITE) && TCPServer.lm.Lock(id, FLIGHT, LockManager.WRITE))) {
                return false;
            }
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }
            undoStack.add("unreserveItem" + "," + id + "," + customerId + "," + Flight.getKey(flightNumber) + "," + flightNumber);
            return myMWRunnable.reserveFlight(id, customerId, flightNumber);
        } catch (DeadlockException e) {
            abort();
            return false;
        }
    }

    @Override
    public boolean reserveCar(int id, int customerId, String location) {
        try {
            renewTTLCountDown();
            if (!(TCPServer.lm.Lock(id, CUSTOMER, LockManager.WRITE) && TCPServer.lm.Lock(id, CAR, LockManager.WRITE))) {
                return false;
            }
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }
            undoStack.add("unreserveItem" + "," + id + "," + customerId + "," + Car.getKey(location) + "," + location);
            return myMWRunnable.reserveCar(id, customerId, location);
        } catch (DeadlockException e) {
            abort();
            return false;
        }
    }

    @Override
    public boolean reserveRoom(int id, int customerId, String location) {
        try {
            renewTTLCountDown();
            if (!(TCPServer.lm.Lock(id, CUSTOMER, LockManager.WRITE) && TCPServer.lm.Lock(id, ROOM, LockManager.WRITE))) {
                return false;
            }
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }
            undoStack.add("unreserveItem" + "," + id + "," + customerId + "," + Room.getKey(location) + "," + location);
            return myMWRunnable.reserveRoom(id, customerId, location);
        } catch (DeadlockException e) {
            abort();
            return false;
        }
    }

    @Override
    public boolean reserveItinerary(int id, int customerId, Vector flightNumbers, String location, boolean car, boolean room) {
        try {
            renewTTLCountDown();
            if(!(TCPServer.lm.Lock(id, CUSTOMER, LockManager.WRITE) &&
            TCPServer.lm.Lock(id, FLIGHT, LockManager.WRITE) &&
            TCPServer.lm.Lock(id, CAR, LockManager.WRITE) &&
            TCPServer.lm.Lock(id, ROOM, LockManager.WRITE))) {
                return false;
            }
            if (this.currentActiveTransactionID != id) {
                System.out.println("transaction id does not match current transaction, command ignored");
                return false;
            }
        } catch (DeadlockException e) {
            abort();
            return false;
        }


        Iterator it = flightNumbers.iterator();

        boolean isSuccessfulReservation = true;
        while (it.hasNext()) {
            try {
                if(reserveFlight(id, customerId, myMWRunnable.getInt(it.next()))) {
                    isSuccessfulReservation = false;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (car) {
            if(reserveCar(id, customerId, location)) {
                isSuccessfulReservation = false;
            }
        }
        if (room) {
            if(reserveRoom(id, customerId, location)) {
                isSuccessfulReservation = false;
            }
        }

        if (isSuccessfulReservation) commit();
        else abort();
        return isSuccessfulReservation;
    }

    @Override
    public boolean increaseReservableItemCount(int id, String key, int count) {
        return false;
    }
}