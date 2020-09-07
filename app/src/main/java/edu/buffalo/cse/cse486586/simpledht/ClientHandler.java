package edu.buffalo.cse.cse486586.simpledht;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClientHandler implements Runnable {

    Socket connectionSocket;
    ContentResolver contentResolver;
    Uri mUri;

    public ClientHandler(Socket connectionSocket, ContentResolver contentResolver, Uri mUri){
        this.connectionSocket = connectionSocket;
        this.contentResolver = contentResolver;
        this.mUri = mUri;
    }

    @Override
    public void run() {
        try{
            DataInputStream inp = new DataInputStream(new BufferedInputStream(connectionSocket.getInputStream()));
            DataOutputStream out = new DataOutputStream(connectionSocket.getOutputStream());

            // read the request type
            String request = inp.readUTF();
            Log.println(Log.DEBUG, SimpleDhtProvider.MY_NODE_ID+" server", "Request:"+request);

            String[] splitRequest = request.split("-");
            String requestType = splitRequest[0];

            if(requestType.equals(Constants.MESSAGE_TYPE_JOIN)){
                /*
                Join request protocol - requestType:RequesterID
                 */
                String requesterNodeId = splitRequest[1];
                Log.println(Log.DEBUG, SimpleDhtProvider.MY_NODE_ID+" server", "Request:"+request+" from "+requesterNodeId);
                Node requesterNode = new Node(requesterNodeId);
                SimpleDhtProvider.serverNodes.add(requesterNode);
                Collections.sort(SimpleDhtProvider.serverNodes, new Comparator<Node>() {
                    @Override
                    public int compare(Node lhs, Node rhs) {
                        return lhs.avdHash.compareTo(rhs.avdHash);
                    }
                });

                // tell the requester node about updated predecessors and successors.
                // tell all other alive nodes about predecessors and successors
                Log.println(Log.DEBUG, SimpleDhtProvider.MY_NODE_ID+" server", "Updating other nodes..");
                int nodesJoined = SimpleDhtProvider.serverNodes.size();
                for (int i = 0; i < nodesJoined; i++){
                    Node node = SimpleDhtProvider.serverNodes.get(i);
                    Node pred = null; Node succ = null;

                    // i points to the smallest node lexicographically
                    if(i == 0){
                        pred = SimpleDhtProvider.serverNodes.get(nodesJoined-1);
                    }else{
                        pred = SimpleDhtProvider.serverNodes.get(i-1);
                    }

                    // i points to the largest node lexicographically
                    if (i == nodesJoined-1){
                        succ = SimpleDhtProvider.serverNodes.get(0);
                    }else{
                        succ = SimpleDhtProvider.serverNodes.get(i+1);
                    }

                    if(node.avdName.equals(Constants.AVD_0)){
                        // if i points to the
                        Log.println(Log.DEBUG, SimpleDhtProvider.MY_NODE_ID+" server", "Self update: pred-"+pred.avdName);
                        Log.println(Log.DEBUG, SimpleDhtProvider.MY_NODE_ID+" server", "Self update: succ-"+succ.avdName);
                        SimpleDhtProvider.pred = pred;
                        SimpleDhtProvider.succ = succ;
                    }else  if (node.avdName.equals(requesterNode.avdName)){
                        // tell the requester it's new pred and succ
                        Log.println(Log.DEBUG, SimpleDhtProvider.MY_NODE_ID+" server",
                                "Sending updated pred "+pred.avdName+" to requester:"+requesterNodeId);
                        Log.println(Log.DEBUG, SimpleDhtProvider.MY_NODE_ID+" server",
                                "Sending update succ "+ succ.avdName +"to requester:"+requesterNodeId);
                        String response = pred.avdName+":"+succ.avdName;
                        out.writeUTF(response);
                        //connectionSocket.close();
                    }else {

                        // this is a 3rd person socket that is not involved
                        // in the communication. It needs to be told the
                        // updated pred, and succ

                        // open a new connection
                        Socket socket = new Socket(InetAddress.getByAddress(
                                new byte[]{10, 0, 2, 2}), node.portNumber);

                        DataInputStream inpNew = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                        DataOutputStream outNew = new DataOutputStream(socket.getOutputStream());

                        // send the updated pred and successor. The prefix MESSAGE_TYPE_JOIN_UPDATE
                        // is important since the receiver has to know what the message is about.
                        String message = Constants.MESSAGE_TYPE_JOIN_UPDATE + "-" + pred.avdName + ":" + succ.avdName;
                        outNew.writeUTF(message);
                        //socket.close();
                    }
                }
            }
            if(requestType.equals(Constants.MESSAGE_TYPE_INSERT)){
                String insertionData = splitRequest[1];
                String[] keyValue = insertionData.split(":");
                String key = keyValue[0];
                String value = keyValue[1];

                Log.println(Log.DEBUG, "Server at: "+SimpleDhtProvider.MY_NODE_ID,
                        "Insert request for key:"+key);

                ContentValues cv = new ContentValues();
                cv.put("key", key);
                cv.put("value", value);

                // call the local content provider to check if data can be inserted on this AVD
                // else it will be forwarded
                contentResolver.insert(mUri, cv);
                //connectionSocket.close();
            }
            if(requestType.equals(Constants.MESSAGE_TYPE_QUERY)){
                String key = splitRequest[1];
                if(key.equals(SimpleDhtProvider.SELECTION_ALL)) {
                    // * will be passed on as a query parameter only to the node_0(5554)
                    String nodesAlive = "";
                    for (Node node : SimpleDhtProvider.serverNodes){
                        nodesAlive += node.avdName+":";
                    }
                    nodesAlive = nodesAlive.substring(0, nodesAlive.length() - 1);
                    out.writeUTF(nodesAlive);
                }else if(key.equals(SimpleDhtProvider.SELECTION_LOCAL)){
                    // query the local content provider
                    Cursor result = contentResolver.query(mUri, null, SimpleDhtProvider.SELECTION_LOCAL, null, null);
                    if(result != null) {
                        String stringResult = Utils.convertCursorToString(result);
                        out.writeUTF(stringResult);
                    }else {
                        out.writeUTF(Constants.EMPTY_RESULT);
                    }
                }else{
                    // a key has been queried
                    Cursor result = contentResolver.query(mUri, null, key, null, null);
                    if(result == null){
                        out.writeUTF(Constants.EMPTY_RESULT);
                    }else {
                        String strResult = Utils.convertCursorToString(result);
                        out.writeUTF(strResult);
                    }
                }
            }
            if(requestType.equals(Constants.MESSAGE_TYPE_DELETE)){
                String key = splitRequest[1];
                if(key.equals(SimpleDhtProvider.SELECTION_ALL)){
                    // * will be passed on as a query parameter only to the node_0(5554)
                    String nodesAlive = "";
                    for (Node node : SimpleDhtProvider.serverNodes){
                        nodesAlive += node.avdName+":";
                    }
                    nodesAlive = nodesAlive.substring(0, nodesAlive.length() - 1);
                    out.writeUTF(nodesAlive);
                }else if(key.equals(SimpleDhtProvider.SELECTION_LOCAL)){
                    // delete everything at this local node
                    contentResolver.delete(mUri, SimpleDhtProvider.SELECTION_LOCAL, null);
                    connectionSocket.close();
                }else{
                    // delete a specific key
                    contentResolver.delete(mUri, key, null);
                    connectionSocket.close();
                }
            }
            if(requestType.equals(Constants.MESSAGE_TYPE_JOIN_UPDATE)){
                // expectation - Constants.MESSAGE_TYPE_JOIN_UPDATE-pred_id:succ_id
                String message = splitRequest[1];
                String[] subMessage = message.split(":");
                String pred = subMessage[0];
                String succ = subMessage[1];
                SimpleDhtProvider.pred = new Node(pred);
                SimpleDhtProvider.succ = new Node(succ);
                connectionSocket.close();
            }
        } catch (IOException e) {
            //publishProgress(e.getMessage());
            e.printStackTrace();
            Log.println(Log.ERROR, "Server:"+SimpleDhtProvider.MY_NODE_ID,
                    "IOEXCEPTIon occured bro!");
        }
    }
}