package edu.buffalo.cse.cse486586.simpledht;

import android.util.Log;
import android.widget.Toast;

import java.security.NoSuchAlgorithmException;

public class Node {
    String avdName;
    String avdHash;
    int portNumber;

    public Node(String avdName){
        this.avdName = avdName;
        this.portNumber = Integer.parseInt(avdName) * 2;
        try {
            this.avdHash = SimpleDhtProvider.genHash(avdName);
        }catch (NoSuchAlgorithmException exception){
            Log.println(Log.DEBUG, "Node", exception.getMessage());
        }
    }

    @Override
    public String toString() {
        return "AvdName:"+avdName+", Hash:"+avdHash+", Port:"+portNumber;
    }
}
