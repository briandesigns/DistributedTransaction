package TransactionManager;

import ResourceManager.TCPServer;
import ResourceManager.ResourceManager;

import ResourceManager.RMHashtable;

import ResourceManager.MiddlewareRunnable;

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
 *
 * before any client delete() is executed, TM first query(), queryPrice(), queryReserved()(not implemented), and save location, count, price, reserved
 * then perform delete(), which deletes also customer reservations as well?
 * if abort, add(location, count, price, reserved), add back customer reservations on those items? (saved into a table of things that need to be executed on abort())
 *
 * before any client reserve() is executed, TM first query(), queryPrice(), queryReserved(), and save ito location, count, price, reserved
 * then  perform reserve()
 * if abort, add(location, +1, price, -1), delete client reservedItem (save this into a  table of things that need to be executed on abort())
 *
 * itinerary should be just implemented as a transaction itself
 *
 * the list of things that needs to be executed on abort() should just be a list of commands to feed to run() of middleware
 */
public class TransactionManager implements ResourceManager {
    public static RMHashtable transactionTable;
    private static int availableXID = 0;
    private MiddlewareRunnable myMWRunnable;
    private boolean inTransaction = false;
    private Thread TTLCountDownThread;
    private static final int TTL_MS = 10000;

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
                    setInTransaction(false);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        TTLCountDownThread.start();
    }

    public TransactionManager(){};

    public TransactionManager(MiddlewareRunnable myMWRunnable) {
        this.myMWRunnable = myMWRunnable;
    }

    public static synchronized int generateXID() {
        availableXID++;
        return availableXID;
    }

    public boolean start() {
        if (!isInTransaction()) {
            startTTLCountDown();
            setInTransaction(true);
            return true;
        } else return false;

    }

    public boolean abort() {
        TTLCountDownThread.interrupt();
        setInTransaction(false);
        //todo: code for reverting all commits or what not
        return true;
    }

    public boolean commit() {
        //todo: code for committing
        return true;
        //todo: if error, then call abort then return false;
    }

    @Override
    public boolean addFlight(int id, int flightNumber, int numSeats, int flightPrice) {
        TCPServer.lm.lock()
    }

    @Override
    public boolean deleteFlight(int id, int flightNumber) {
        return false;
    }

    @Override
    public int queryFlight(int id, int flightNumber) {
        return 0;
    }

    @Override
    public int queryFlightPrice(int id, int flightNumber) {
        return 0;
    }

    @Override
    public boolean addCars(int id, String location, int numCars, int carPrice) {
        return false;
    }

    @Override
    public boolean deleteCars(int id, String location) {
        return false;
    }

    @Override
    public int queryCars(int id, String location) {
        return 0;
    }

    @Override
    public int queryCarsPrice(int id, String location) {
        return 0;
    }

    @Override
    public boolean addRooms(int id, String location, int numRooms, int roomPrice) {
        return false;
    }

    @Override
    public boolean deleteRooms(int id, String location) {
        return false;
    }

    @Override
    public int queryRooms(int id, String location) {
        return 0;
    }

    @Override
    public int queryRoomsPrice(int id, String location) {
        return 0;
    }

    @Override
    public int newCustomer(int id) {
        return 0;
    }

    @Override
    public boolean newCustomerId(int id, int customerId) {
        return false;
    }

    @Override
    public boolean deleteCustomer(int id, int customerId) {
        return false;
    }

    @Override
    public String queryCustomerInfo(int id, int customerId) {
        return null;
    }

    @Override
    public boolean reserveFlight(int id, int customerId, int flightNumber) {
        return false;
    }

    @Override
    public boolean reserveCar(int id, int customerId, String location) {
        return false;
    }

    @Override
    public boolean reserveRoom(int id, int customerId, String location) {
        return false;
    }

    @Override
    public boolean reserveItinerary(int id, int customerId, Vector flightNumbers, String location, boolean car, boolean room) {
        return false;
    }

    @Override
    public boolean increaseReservableItemCount(int id, String key, int count) {
        return false;
    }
}
