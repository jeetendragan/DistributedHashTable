package edu.buffalo.cse.cse486586.simpledht;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Formatter;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

public class SimpleDhtProvider extends ContentProvider {

    static Node pred = null;
    static Node succ = null;
    static String MY_PORT, MY_NODE_ID, MY_HASH;
    static String RECORDS_FILE = "RECORDS_FILE";
    static String SELECTION_ALL = "*";
    static String SELECTION_LOCAL = "@";

    // this is going to be only useful at the AVD_0
    static ArrayList<Node> serverNodes;

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if(pred == null && succ == null){
            if (selection.equals(SELECTION_ALL) || selection.equals(SELECTION_LOCAL)) {
                deleteAllLocalRecords();
                initializeRecordsFile();
            }else{
                deleteKeyInRing(selection);
            }
        }else{
            if (selection.equals(SELECTION_ALL)){
                // Send a request to the AVD_0 which sends a delete request to everyone
                AsyncTask.execute(new Runnable() {
                    @Override
                    public void run() {
                        Socket socToAVD0 = null;
                        try {
                            socToAVD0 = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                    Constants.REMOTE_PORT0);
                            DataInputStream inp = new DataInputStream(new BufferedInputStream(socToAVD0.getInputStream()));
                            DataOutputStream out = new DataOutputStream(socToAVD0.getOutputStream());
                            String command = Constants.MESSAGE_TYPE_DELETE+"-"+SimpleDhtProvider.SELECTION_ALL;
                            out.writeUTF(command);

                            // expects a list of all the alive AVDs
                            String nodesAlive = inp.readUTF();
                            String[] nodesAliveList = nodesAlive.split(":");
                            command = Constants.MESSAGE_TYPE_DELETE+"-"+SELECTION_LOCAL;
                            for (String aliveNode: nodesAliveList){
                                Socket socketToAnother = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                        Utils.avdNameToPort(aliveNode));
                                DataInputStream inpToAnother = new DataInputStream(new BufferedInputStream(socketToAnother.getInputStream()));
                                DataOutputStream outToAnother = new DataOutputStream(socketToAnother.getOutputStream());
                                outToAnother.writeUTF(command);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            Log.println(Log.ERROR, "Server:"+SimpleDhtProvider.MY_NODE_ID,
                                    "IOEXCEPTIon occured bro!");
                        }
                    }
                });
            }else if (selection.equals(SELECTION_LOCAL)){
                // delete all the local data
                deleteAllLocalRecords();
                initializeRecordsFile();
            }else{
                deleteKeyInRing(selection);
            }
        }
        return 0;
    }

    private void deleteKeyInRing(String key) {
        String hashedKey = null;
        try {
            hashedKey = SimpleDhtProvider.genHash(key);
            MY_HASH = SimpleDhtProvider.genHash(MY_NODE_ID);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID, "No such algo");
            return;
        }

        if (pred == null && succ == null){
            // this node is responsible for the key
            deleteLocalKey(key);
            return;
        }

        if(amIFirst()){
            Log.println(Log.DEBUG, MY_NODE_ID, "I am the first node!");
            if (isFirstGreater(hashedKey, pred.avdHash)){ // hashedKey > predHash
                Log.println(Log.DEBUG, MY_NODE_ID, "Key greater than pred. I am responsible for the key: "+key);
                deleteLocalKey(key);
            }else{ // hashedKey <= predHash
                if(!isFirstGreater(hashedKey, MY_HASH)){ // key <= my_hash
                    Log.println(Log.DEBUG, MY_NODE_ID, "Key <= myHash. Delete called");
                    deleteLocalKey(key);
                }else{ // key > my_hash
                    // forward the insert to successor
                    Log.println(Log.DEBUG, MY_NODE_ID, "Key > myHash. Forwarding to AVD"+succ.avdName);
                    deleteKeyFromSuccessor(key);
                }
            }
        }else{
            if (isFirstGreater(hashedKey, MY_HASH)){ // hashedKey > myHash
                // open a client socket and send content values to the successor
                Log.println(Log.DEBUG, MY_NODE_ID, "I am not the 1st node. hashedKey > myHash. Forwarding to AVD"+succ.avdName);
                deleteKeyFromSuccessor(key);
            }else{ // hashedKey <= myHash
                if(isFirstGreater(hashedKey, pred.avdHash)){ // hashedKey > prevHash
                    Log.println(Log.DEBUG, MY_NODE_ID, "I am not the 1st node. " +
                            "hashedKey <= myHash & hashedKey>prevHash. I am responsible.");
                    deleteLocalKey(key);
                }else{ // hashedKey <= prevHash
                    // forware the request to successor
                    Log.println(Log.DEBUG, MY_NODE_ID, "I am not the 1st node. hashedKey <= myHash& hashedKey <= prevHash" +
                            "This key belongs to someone behind me. Forwarding to AVD"+succ.avdName);
                    deleteKeyFromSuccessor(key);
                }
            }
        }
    }

    private void deleteKeyFromSuccessor(final String key) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                String command = Constants.MESSAGE_TYPE_DELETE+"-"+key;
                try {
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), succ.portNumber);
                    DataInputStream inp = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeUTF(command);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void deleteLocalKey(String key) {
        MatrixCursor cursor = readAllLocalRecords();
        deleteAllLocalRecords();
        initializeRecordsFile();
        while (cursor.moveToNext()){
            String existingKey = cursor.getString(cursor.getColumnIndex("key"));
            String existingValue = cursor.getString(cursor.getColumnIndex("value"));
            if(!existingKey.equals(key)){
                insertRecordLocally(existingKey, existingValue);
            }
        }
    }

    private void deleteAllLocalRecords() {
        File file = new File(this.getContext().getFilesDir(), RECORDS_FILE);
        if (file.exists()) {
            try {
                file.delete();
            }catch (Exception e){
                e.printStackTrace();
                Log.println(Log.DEBUG, "Init:"+MY_NODE_ID, "Exception. Could not delete the RECORDS_FILE");;
            }
        }
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Log.println(Log.DEBUG, MY_NODE_ID, "Insert called");
        try{
            MY_HASH = SimpleDhtProvider.genHash(MY_NODE_ID);
        }
        catch (Exception ex){
            Log.println(Log.DEBUG, "Msg:"+MY_NODE_ID, "MY_HASH could not be computed!");
        }
        // compute the hash of the key to be inserted
        // implement chord routing to find the next node to pass this key to (This node has to know the hash-code of all other nodes)
        String key = (String)values.get("key");
        String value = (String) values.get("value");
        String hashedKey = null;
        try {
            hashedKey = SimpleDhtProvider.genHash(key);
        }catch (Exception e){
            Log.println(Log.DEBUG, "Exception:"+MY_NODE_ID,"Hash method not found!");
            return null;
        }

        if(SimpleDhtProvider.pred == null && SimpleDhtProvider.succ == null){
            // there is only one node in the ring. Add the key value here itself.
            Log.println(Log.DEBUG, MY_NODE_ID, "There is only one node in the system, and this is the one!");
            insertRecordLocally(key, value);
            return null;
        }
        if(amIFirst()){
            Log.println(Log.DEBUG, MY_NODE_ID, "I am the first node!");
            if (isFirstGreater(hashedKey, pred.avdHash)){ // hashedKey > predHash
                Log.println(Log.DEBUG, MY_NODE_ID, "Key greater than pred. I am responsible for the key: "+key);
                insertRecordLocally(key, value);
            }else{ // hashedKey <= predHash
                if(!isFirstGreater(hashedKey, MY_HASH)){ // key <= my_hash
                    Log.println(Log.DEBUG, MY_NODE_ID, "Key <= myHash. Insert called");
                    insertRecordLocally(key, value);
                }else{ // key > my_hash
                    // forward the insert to successor
                    Log.println(Log.DEBUG, MY_NODE_ID, "Key > myHash. Forwarding to AVD"+succ.avdName);
                    forwardInsertToSuccessor(key, value);
                }
            }
        }else{
            if (isFirstGreater(hashedKey, MY_HASH)){ // hashedKey > myHash
                // open a client socket and send content values to the successor
                Log.println(Log.DEBUG, MY_NODE_ID, "I am not the 1st node. hashedKey > myHash. Forwarding to AVD"+succ.avdName);
                forwardInsertToSuccessor(key, value);
            }else{ // hashedKey <= myHash
                if(isFirstGreater(hashedKey, pred.avdHash)){ // hashedKey > prevHash
                    Log.println(Log.DEBUG, MY_NODE_ID, "I am not the 1st node. " +
                            "hashedKey <= myHash & hashedKey>prevHash. I am responsible.");
                    insertRecordLocally(key, value);
                }else{ // hashedKey <= prevHash
                    // forware the request to successor
                    Log.println(Log.DEBUG, MY_NODE_ID, "I am not the 1st node. hashedKey <= myHash& hashedKey <= prevHash" +
                            "This key belongs to someone behind me. Forwarding to AVD"+succ.avdName);
                    forwardInsertToSuccessor(key, value);
                }
            }
        }
        /*if iamFirst():
            if key > predecessor_hash
                me_add(key)
            else: # key <= predecessor_hash
                if key <= my_hash:
                    me_add(key)
                else:
                    successor.insert(key)
                    // open a client socket and send a content values to the successor
        else:
            if key > my_hash:
                successor.insert(key)
            else: # key <= my_hash
                if key > predecessor_hash
                    me_add(key)
                else:
                    successor.insert(key)*/

        // else. if this node is responsible for key, then add it here.

        return null;
    }

    private void forwardInsertToSuccessor(final String key, final String value) {
        // open a socket with the successor avd
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), succ.portNumber);
                    DataInputStream inp = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    String outputMessage = Constants.MESSAGE_TYPE_INSERT+"-"+key+":"+value;
                    out.writeUTF(outputMessage);
                    //socket.close();
                }catch(Exception exception){
                    Log.println(Log.DEBUG, MY_NODE_ID+" join requestor", "AVD_0 unreachable!");
                    SimpleDhtProvider.pred = null;
                    SimpleDhtProvider.succ = null;
                }
            }
        });
    }

    private boolean amIFirst() {
        assert pred != null;
        int result = MY_HASH.compareTo(pred.avdHash);
        return result < 0;
    }

    private boolean isFirstGreater(String first, String second){
        int result = first.compareTo(second);
        return result > 0;
    }

    @Override
    public boolean onCreate() {
        if(!initializeRecordsFile()){
            Log.println(Log.DEBUG, "Init", "Could not create the RECORDS_FILE");
            return false;
        }
        Log.println(Log.DEBUG, "Init", "Initialization successful!");

        TelephonyManager tel = (TelephonyManager)  getContext().getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        MY_PORT = Integer.parseInt(portStr) * 2+"";
        MY_NODE_ID = portStr;

        if(MY_NODE_ID.equals(Constants.AVD_0)){
            serverNodes = new ArrayList<Node>();
            serverNodes.add(new Node(MY_NODE_ID));
        }else{
            serverNodes = null;
        }

        // start a server on port 10000, and listen for requests(on a thread)
        try {
            ServerSocket serverSocket = new ServerSocket(Constants.SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
            //Toast.makeText(this, "Server socket created! ", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(getContext().getApplicationContext(),
                    "Can't create a server socket!", Toast.LENGTH_LONG).show();
        }

        /*
        if my_port == 5554
            pred = null and succ = null
        else
            // talk to port 5554
            // if 5554 is unreachable
                // then set pred == null and succ = null
            else
                // set pred and succ to the value sent by the avd-5554
        */

        // set up the successor and predecessor
        if(MY_NODE_ID.equals(Constants.AVD_0)){
            // when AVD_0 wakes up, it is the first one to wake up.
            Log.println(Log.DEBUG, MY_NODE_ID,"AVD_0 has woken up. Setting default pred and succ.");
            pred = null;
            succ = null;
        }else{ // not AVD_0
            // try contacting the AVD_0

            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.println(Log.DEBUG, MY_NODE_ID + " join requestor", "Join request");
                        Socket socToAVD0 = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Constants.REMOTE_PORT0);

                        DataInputStream inp = new DataInputStream(new BufferedInputStream(socToAVD0.getInputStream()));
                        DataOutputStream out = new DataOutputStream(socToAVD0.getOutputStream());

                        // send a join request
                        out.writeUTF(Constants.MESSAGE_TYPE_JOIN + "-" + MY_NODE_ID);

                        // wait for the AVD_0 to respond with the pred, and succ
                        String response = inp.readUTF();
                        Log.println(Log.DEBUG, MY_NODE_ID + " join requestor", "Response: " + response);

                        // parse the response
                        String[] splitResponse = response.split(":");

                        String predName = splitResponse[0];
                        String succName = splitResponse[1];

                        SimpleDhtProvider.pred = new Node(predName);
                        SimpleDhtProvider.succ = new Node(succName);

                        Log.println(Log.DEBUG, MY_NODE_ID + " join requestor", "pred and succ updated!");

                        socToAVD0.close();
                    }catch(Exception exception){
                        Log.println(Log.DEBUG, MY_NODE_ID+" join requestor", "AVD_0 unreachable!");
                        SimpleDhtProvider.pred = null;
                        SimpleDhtProvider.succ = null;
                    }
                }
            });
        }


        /*
        -----------Data Structure---
        alive_list <- list of nodes{emulator_number, hash, port} that are alive
        -----------Data Structure---

        --------------------Message format------
        request_type:request_specific_info
        --------------------Message format------

        when a request is received
        Request types:
            1) Join request(Only specific to the 5554 or master node)
            2) Insert request
            3) Delete request
            4) Query request


            1) ---------------- Join request -----------------------
            --------Request specific info-----------
            emulator_number
            --------Request specific info-----------
            hash_em = hash(emulator_name)
            n <- create a new node with {emulator_number, hash_em, emulator_number*2}
            add n to alive_list
            sort alive_list by hash

            contact every node and tell them who their successor and predecessor is
            -------------------- Join request -----------------------

            2) ----------------- Insert request ---------------------
         */
        return false;
    }

    private boolean initializeRecordsFile() {
        File file = new File(this.getContext().getFilesDir(), RECORDS_FILE);
        if (!file.exists()) {
            try {
                return file.createNewFile();
            }catch (Exception e){
                e.printStackTrace();
                Log.println(Log.DEBUG, "Init:"+MY_NODE_ID, "Exception. Could not create the RECORDS_FILE");
                return false;
            }
        }
        return true;
    }

    private boolean insertRecordLocally(String key, String value) {
        Log.println(Log.DEBUG, MY_NODE_ID, "Inserting locally: key:"+key+", value:"+value);
        // open the records file
        File file = new File(this.getContext().getFilesDir(), RECORDS_FILE);
        FileWriter writer = null;
        try {
            writer = new FileWriter(file, true);
            String row = key+":"+value+"\n";
            Log.println(Log.DEBUG, MY_NODE_ID, "Inserting :"+row);
            writer.append(row);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
            Log.println(Log.DEBUG, "Exception:"+MY_NODE_ID, "Could not append the file.");
            return false;
        }
        logLocalRecordContents();
        return true;
    }

    private void logLocalRecordContents() {
        File file = new File(this.getContext().getFilesDir(), RECORDS_FILE);
        try {
            FileReader reader = new FileReader(file);
            BufferedReader bufferedReader = new BufferedReader(reader);
            String line = null;

            while ((line = bufferedReader.readLine()) != null){
                Log.println(Log.DEBUG, "RECORD_ROW", line);
            }
            bufferedReader.close();
            reader.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID,
                    "FilenOtfound");
        } catch (IOException e) {
            e.printStackTrace();
            Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID,
                    "FilenOtfound");
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        Log.println(Log.DEBUG, MY_NODE_ID, "Insert called");
        try{
            MY_HASH = SimpleDhtProvider.genHash(MY_NODE_ID);
        }
        catch (Exception ex){
            Log.println(Log.DEBUG, "Exception: "+MY_NODE_ID, "MY_HASH could not be computed!");
        }

        if (pred == null && succ == null){
            // this is the only node in the ring
            if (selection.equals(SELECTION_ALL) || selection.equals(SELECTION_LOCAL)){
                MatrixCursor cursor = readAllLocalRecords();
                return cursor;
            }else{
                MatrixCursor cursor = fetchRecordForKey(selection);
                return cursor;
            }
        }else{
            if(selection.equals(SELECTION_ALL)){
                // send a request to AVD_0 which will fetch the local data from all the nodes, and merge it and return
                MatrixCursor cursor = getAllGlobalRecords();
                return cursor;
            }else if (selection.equals(SELECTION_LOCAL)){
                MatrixCursor cursor = readAllLocalRecords();
                return cursor;
            }else{
                // a key has been queried
                MatrixCursor cursor = fetchRecordForKey(selection);
                return cursor;
            }
        }
    }

    private MatrixCursor fetchRecordForKey(String key) {
        String hashedKey = null;
        try {
            hashedKey = SimpleDhtProvider.genHash(key);
            MY_HASH = SimpleDhtProvider.genHash(MY_NODE_ID);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID, "No such algo");
            return null;
        }

        if (pred == null && succ == null){
            // this node is responsible for the key
            MatrixCursor cursor = fetchLocalRecordForKey(key);
            return cursor;
        }

        if(amIFirst()){
            Log.println(Log.DEBUG, MY_NODE_ID, "I am the first node!");
            if (isFirstGreater(hashedKey, pred.avdHash)){ // hashedKey > predHash
                Log.println(Log.DEBUG, MY_NODE_ID, "Key greater than pred. I am responsible for the key: "+key);
                MatrixCursor cursor = fetchLocalRecordForKey(key);
                return cursor;
            }else{ // hashedKey <= predHash
                if(!isFirstGreater(hashedKey, MY_HASH)){ // key <= my_hash
                    Log.println(Log.DEBUG, MY_NODE_ID, "Key <= myHash. Insert called");
                    MatrixCursor cursor = fetchLocalRecordForKey(key);
                    return cursor;
                }else{ // key > my_hash
                    // forward the insert to successor
                    Log.println(Log.DEBUG, MY_NODE_ID, "Key > myHash. Forwarding to AVD"+succ.avdName);
                    MatrixCursor cursor = fetchRecordFroKeyFromSuccessor(key);
                    return  cursor;
                }
            }
        }else{
            if (isFirstGreater(hashedKey, MY_HASH)){ // hashedKey > myHash
                // open a client socket and send content values to the successor
                Log.println(Log.DEBUG, MY_NODE_ID, "I am not the 1st node. hashedKey > myHash. Forwarding to AVD"+succ.avdName);
                MatrixCursor cursor = fetchRecordFroKeyFromSuccessor(key);
                return cursor;
            }else{ // hashedKey <= myHash
                if(isFirstGreater(hashedKey, pred.avdHash)){ // hashedKey > prevHash
                    Log.println(Log.DEBUG, MY_NODE_ID, "I am not the 1st node. " +
                            "hashedKey <= myHash & hashedKey>prevHash. I am responsible.");
                    MatrixCursor cursor = fetchLocalRecordForKey(key);
                    return cursor;
                }else{ // hashedKey <= prevHash
                    // forware the request to successor
                    Log.println(Log.DEBUG, MY_NODE_ID, "I am not the 1st node. hashedKey <= myHash& hashedKey <= prevHash" +
                            "This key belongs to someone behind me. Forwarding to AVD"+succ.avdName);
                    MatrixCursor cursor = fetchRecordFroKeyFromSuccessor(key);
                    return cursor;
                }
            }
        }
    }

    private MatrixCursor fetchRecordFroKeyFromSuccessor(String key) {
        GetRecordFromSuccessorThread thread = new GetRecordFromSuccessorThread(key);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
            Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID, "InterruptedExc");
        }
        return thread.cursor;
    }

    private MatrixCursor fetchLocalRecordForKey(String key) {
        File file = new File(this.getContext().getFilesDir(), RECORDS_FILE);
        String value = null;
        try {
            FileReader reader = new FileReader(file);
            BufferedReader bufferedReader = new BufferedReader(reader);
            String line = null;
            while ((line = bufferedReader.readLine()) != null){
                String[] splitLine = line.split(":");
                if(splitLine[0].equals(key)){
                    value = splitLine[1];
                    break;
                }
            }
            bufferedReader.close();
            reader.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID, "Filenotfound");
        } catch (IOException e) {
            e.printStackTrace();
            Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID, "IOExc");
        }

        if(value == null){
            return null;
        }else {
            MatrixCursor cursor = new MatrixCursor(new String[]{"key", "value"});
            cursor.addRow(new String[]{key, value});
            return cursor;
        }
    }

    private MatrixCursor getAllGlobalRecords() {
        // talk to AVD_0 and fetch all the key-value pairs
        GetGlobalRecordsThread thread = new GetGlobalRecordsThread();
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
            Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID, "InterruptedExc");
        }
        return thread.cursor;
    }

    private MatrixCursor readAllLocalRecords() {
        MatrixCursor cursor = null;
        File file = new File(this.getContext().getFilesDir(), RECORDS_FILE);
        try {
            cursor = new MatrixCursor(new String[]{"key", "value"});
            FileReader reader = new FileReader(file);
            BufferedReader bufferedReader = new BufferedReader(reader);
            String line = null;
            int recordCount = 0;
            while ((line = bufferedReader.readLine()) != null){
                // Log.println(Log.DEBUG, "RECORD_ROW", line);
                String[] splitLine = line.split(":");
                cursor.addRow(splitLine);
                recordCount++;
            }

            if(recordCount == 0){
                cursor = null;
            }

            bufferedReader.close();
            reader.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID, "FileNotFound");
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID, "IOExc");
            return null;
        }
        return cursor;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    public static String genHash(String input) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] sha1Hash = sha1.digest(input.getBytes());
        Formatter formatter = new Formatter();
        for (byte b : sha1Hash) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }

    public class ServerTask extends AsyncTask<ServerSocket, String, Void> {

        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            Uri.Builder uriBuilder = new Uri.Builder();
            uriBuilder.authority("edu.buffalo.cse.cse486586.simpledht.provider");
            uriBuilder.scheme("content");
            Uri mUri = uriBuilder.build();

            ServerSocket serverSocket = sockets[0];
            Socket connectionSocket = null;
            Looper.prepare();
            Handler handler = new Handler();
            try {
                while(true)
                {
                    Log.println(Log.DEBUG, "Server", "Waiting for clients to connect");
                    connectionSocket = serverSocket.accept();
                    Log.println(Log.DEBUG, "Server", "Conneted to some client!");

                    ContentResolver contentResolver = getContext().getApplicationContext().getContentResolver();

                    // Spawn a new thread to handle the connection
                    Runnable r = new ClientHandler(connectionSocket, contentResolver, mUri);
                    //handler.post(r);
                    Thread th = new Thread(r);
                    th.start();
                }
            } catch (IOException e) {
                publishProgress(e.getMessage());
                e.printStackTrace();
                Log.println(Log.ERROR, "Server:"+MY_NODE_ID, e.getMessage());
            }
            return null;
        }

        protected void onProgressUpdate(String...strings) {
            Log.println(Log.DEBUG, "Server","Writing "+strings[0]+" to UI on server");
        /*String strReceived = strings[0].trim();
        TextView remoteTextView = (TextView) findViewById(R.id.textView1);
        remoteTextView.append(strReceived + "\n");*/
            return;
        }
    }

    public class GetGlobalRecordsThread extends Thread{

        public MatrixCursor cursor;

        GetGlobalRecordsThread()
        {
            this.cursor = new MatrixCursor(new String[]{"key", "value"});
        }

        public void run() {
            this.cursor = new MatrixCursor(new String[]{"key", "value"});
            Socket socToAVD0 = null;
            try {
                socToAVD0 = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                        Constants.REMOTE_PORT0);
                DataInputStream inp = new DataInputStream(new BufferedInputStream(socToAVD0.getInputStream()));
                DataOutputStream out = new DataOutputStream(socToAVD0.getOutputStream());
                String message = Constants.MESSAGE_TYPE_QUERY+"-"+SELECTION_ALL;
                out.writeUTF(message);

                String aliveNodes = inp.readUTF();

                String[] aliveNodesList = aliveNodes.split(":");

                for (String aliveNode: aliveNodesList){
                    if (aliveNode.equals(MY_NODE_ID)){
                        Cursor localData = readAllLocalRecords();
                        if(localData != null){
                            while (localData.moveToNext()){
                                String locKey = localData.getString(localData.getColumnIndex("key"));
                                String locVal = localData.getString(localData.getColumnIndex("value"));
                                this.cursor.addRow(new String[]{locKey, locVal});
                            }
                        }
                    }else{
                        // open a socket talk to another alive node and get its local data
                        Socket socketToAnother = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Utils.avdNameToPort(aliveNode));

                        DataInputStream inpToAnother = new DataInputStream(new BufferedInputStream(socketToAnother.getInputStream()));
                        DataOutputStream outToAnother = new DataOutputStream(socketToAnother.getOutputStream());

                        String command = Constants.MESSAGE_TYPE_QUERY+"-"+SELECTION_LOCAL;
                        outToAnother.writeUTF(command);
                        String othersLocalRecord = inpToAnother.readUTF();
                        socketToAnother.close();
                        if(othersLocalRecord.equals(Constants.EMPTY_RESULT)){
                            continue;
                        }
                        String[] globalKeyValuePairs = othersLocalRecord.split(",");
                        for (String keyValue: globalKeyValuePairs){
                            String[] splitKeyValue = keyValue.split(":");
                            this.cursor.addRow(splitKeyValue);
                        }

                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
                Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID, "IOExce");
            }
        }
    }

    public class GetRecordFromSuccessorThread extends Thread{

        public MatrixCursor cursor;
        public String key;

        GetRecordFromSuccessorThread(String key)
        {
            this.cursor = new MatrixCursor(new String[]{"key", "value"});
            this.key = key;
        }

        public void run() {
            this.cursor = new MatrixCursor(new String[]{"key", "value"});
            Socket socToSuccessor = null;
            try {
                socToSuccessor = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                        succ.portNumber);
                DataInputStream inp = new DataInputStream(new BufferedInputStream(socToSuccessor.getInputStream()));
                DataOutputStream out = new DataOutputStream(socToSuccessor.getOutputStream());
                String message = Constants.MESSAGE_TYPE_QUERY+"-"+this.key;
                out.writeUTF(message);

                // read all the key value pairs
                String record = inp.readUTF();
                if(record.equals(Constants.EMPTY_RESULT)){
                    cursor = null;
                }else{
                    String[] splitRecord = record.split(":");
                    cursor.addRow(splitRecord);
                }
            } catch (IOException e) {
                e.printStackTrace();
                Log.println(Log.ERROR, "Exception:"+SimpleDhtProvider.MY_NODE_ID, "IOExcept");
            }
        }
    }

}
