package edu.buffalo.cse.cse486586.simpledht;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.app.Activity;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.http.client.UserTokenHandler;
import org.w3c.dom.Text;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class SimpleDhtActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_dht_main);

        final TextView tv = (TextView) findViewById(R.id.textView1);
        tv.setMovementMethod(new ScrollingMovementMethod());
        findViewById(R.id.button3).setOnClickListener(
                new OnTestClickListener(tv, getContentResolver()));

        final TextView tvLog = (TextView) findViewById(R.id.tvLog);

        final EditText etInsert = (EditText) findViewById(R.id.etInsertValue);

        Button btnInsert = (Button) findViewById(R.id.button4);
        btnInsert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String insertKeyValue = (String) etInsert.getText().toString();
                String[] splitInsert = insertKeyValue.split(":");
                String key = splitInsert[0];
                String value = splitInsert[1];

                ContentValues contentValues = new ContentValues();
                contentValues.put("key", key);
                contentValues.put("value", value);
                Uri mUri = Utils.buildUri("content", "edu.buffalo.cse.cse486586.simpledht.provider");
                ContentResolver mContentResolver = getContentResolver();
                mContentResolver.insert(mUri,contentValues);
            }
        });

        Button btnOne = (Button) findViewById(R.id.button1);
        btnOne.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // local dump
                ContentResolver mContentResolver = getContentResolver();
                Uri mUri = Utils.buildUri("content", "edu.buffalo.cse.cse486586.simpledht.provider");
                String key = etInsert.getText().toString();
                Cursor result = mContentResolver.query(mUri, null, key, null, null);
                String str = "";
                if (result == null){
                    str = Constants.EMPTY_RESULT;
                }else {
                    while (result.moveToNext()) {
                        int keyIndex = result.getColumnIndex("key");
                        int valueIndex = result.getColumnIndex("value");
                        String returnKey = result.getString(keyIndex);
                        String returnValue = result.getString(valueIndex);
                        str += returnKey + ":" + returnValue;
                    }
                }
                tvLog.setText(str);
            }
        });

        Button btnTwo = (Button) findViewById(R.id.button2);
        btnTwo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentResolver mContentResolver = getContentResolver();
                Uri mUri = Utils.buildUri("content", "edu.buffalo.cse.cse486586.simpledht.provider");
                Cursor result = mContentResolver.query(mUri, null, "*", null, null);
                String str = "";
                if (result == null) {
                    str = Constants.EMPTY_RESULT;
                }else {
                    while (result.moveToNext()) {
                        int keyIndex = result.getColumnIndex("key");
                        int valueIndex = result.getColumnIndex("value");
                        String returnKey = result.getString(keyIndex);
                        String returnValue = result.getString(valueIndex);
                        str += returnKey + ":" + returnValue;
                    }
                }
                tvLog.setText(str);
            }
        });

        Button btnQuery = (Button) findViewById(R.id.btnQuery);
        btnQuery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentResolver mContentResolver = getContentResolver();
                Uri mUri = Utils.buildUri("content", "edu.buffalo.cse.cse486586.simpledht.provider");
                String key = etInsert.getText().toString();
                Toast.makeText(getApplicationContext(), "Querying "+key, Toast.LENGTH_LONG).show();
                Cursor result = mContentResolver.query(mUri, null, key, null, null);
                String str = "";
                if(result == null){
                    str = Constants.EMPTY_RESULT;
                }else {
                    while (result.moveToNext()) {
                        int keyIndex = result.getColumnIndex("key");
                        int valueIndex = result.getColumnIndex("value");
                        String returnKey = result.getString(keyIndex);
                        String returnValue = result.getString(valueIndex);
                        str += returnKey + ":" + returnValue;
                    }
                }
                tvLog.setText(str);
            }
        });

        Button btnDelete = (Button) findViewById(R.id.btnDelete);
        btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ContentResolver mContentResolver = getContentResolver();
                    Uri mUri = Utils.buildUri("content", "edu.buffalo.cse.cse486586.simpledht.provider");
                    String key = etInsert.getText().toString();
                    mContentResolver.delete(mUri, key, null);
                }catch (Exception exception){
                    Toast.makeText(getApplicationContext(), "Delete exception.", Toast.LENGTH_LONG).show();
                }
            }
        });

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_simple_dht_main, menu);
        return true;
    }

}
