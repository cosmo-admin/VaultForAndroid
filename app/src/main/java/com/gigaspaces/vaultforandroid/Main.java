package com.gigaspaces.vaultforandroid;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toast;

import com.gigaspaces.vaultforandroid.adapters.ListParentClass;
import com.gigaspaces.vaultforandroid.adapters.ListViewAdapter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;

public class Main extends Activity {
    private ListView mListView;
    private ListViewAdapter mListViewAdapter;
    private String mVaultServerIp;
    private String mVaultServerIpPort;
    private String mVaultToken;
    private ListParentClass mCurrentBreadcrumbItem;
    private ArrayList<ListParentClass> mListItems;
    private int mBackKeyPressedCounter;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        setupViews();
    }

    @Override
    protected void onResume() {
        super.onResume();

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        mVaultServerIp = sharedPref.getString(getString(R.string.serverIp), "");
        mVaultServerIpPort = sharedPref.getString(getString(R.string.serverIpPort), "");
        mVaultToken = sharedPref.getString(getString(R.string.token), "");

        if (!mVaultServerIp.isEmpty() && !mVaultServerIpPort.isEmpty() && !mVaultToken.isEmpty()) {
            new DisplaySecretsTask(mVaultServerIp, mVaultServerIpPort, mVaultToken).execute();
            Toast.makeText(this, R.string.toastUpdating, Toast.LENGTH_SHORT).show();
        }

        mBackKeyPressedCounter = 0;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.settings:
                Intent intent = new Intent(this, Settings.class);
                startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        if (mCurrentBreadcrumbItem != null) {
            mBackKeyPressedCounter = 0;
            ListParentClass breadCrumbItemParent = mCurrentBreadcrumbItem.getParent();

            if (breadCrumbItemParent != null) {
                // Display the upper level
                mListViewAdapter.updateParents(breadCrumbItemParent.getChildren());
                mCurrentBreadcrumbItem = breadCrumbItemParent;
            } else {
                // Display root level
                mListViewAdapter.updateParents(mListItems);
                mCurrentBreadcrumbItem = null;
            }
        } else {
            if (mBackKeyPressedCounter >= 1) {
                finish();
            } else {
                mBackKeyPressedCounter++;
                Toast.makeText(this, R.string.toastExit, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setupViews() {
        mListView = (ListView) findViewById(R.id.listView);
        mListViewAdapter = new ListViewAdapter(mListView.getContext(), new ArrayList<ListParentClass>());
        mVaultServerIp = "";
        mVaultServerIpPort = "";
        mVaultToken = "";

        mListView.setAdapter(mListViewAdapter);
        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ListParentClass currentItem = (ListParentClass) mListView.getItemAtPosition(position);
                if (currentItem.isFolder()) {
                    mListViewAdapter.updateParents(currentItem.getChildren());
                    mCurrentBreadcrumbItem = currentItem;
                } else {
                    Intent activityIntent = new Intent(getApplicationContext(), Secret.class);
                    activityIntent.putExtra(getResources().getString(R.string.secretPath),
                            currentItem.constructSecretPath());
                    startActivity(activityIntent);
                }
            }
        });
    }

    private ArrayList<ListParentClass> constructListItemsTree(JSONArray items) {
        ArrayList<ListParentClass> parentArray = new ArrayList<>();
        ListParentClass holder;

        for (int i = 0; i < items.length(); i++) {
            try {
                if (items.get(i) instanceof JSONObject) {
                    JSONObject jsonHolder = (JSONObject) items.get(i);
                    Iterator<?> keys = jsonHolder.keys();

                    if (keys.hasNext()) {
                        String key = (String) keys.next();
                        if (jsonHolder.get(key) instanceof JSONArray) {
                            holder = new ListParentClass(key);
                            holder.setIsFolder(true);
                            holder.setChildren(
                                    constructListItemsTree(
                                            (JSONArray) jsonHolder.get(key)
                                    )
                            );
                            appendParents(holder);

                            parentArray.add(holder);
                        } else {
                            throw new RuntimeException(
                                    "Expected JSONArray, found " + jsonHolder.get(key).getClass()
                            );
                        }
                    } else {
                        throw new RuntimeException("Empty JSONObject");
                    }
                } else {
                    holder = new ListParentClass((String) items.get(i));
                    parentArray.add(holder);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        return parentArray;
    }

    private void appendParents(ListParentClass parent) {
        ArrayList<ListParentClass> children;
        children = parent.getChildren();
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                ListParentClass currentChild = children.get(i);
                currentChild.setParent(parent);
                if (currentChild.getChildren() != null) {
                    appendParents(currentChild);
                }
            }
        }
    }

    class DisplaySecretsTask extends AsyncTask<String, Void, JSONArray> {
        String mVaultServerIp;
        String mVaultToken;
        String mVaultServerIpPort;

        public DisplaySecretsTask(String vaultIp, String vaultIpPort, String token) {
            mVaultServerIp = vaultIp;
            mVaultServerIpPort = vaultIpPort;
            mVaultToken = token;
        }

        private JSONArray getSecrets(String folderPath) {
            HttpURLConnection urlConnection = null;
            JSONObject jsonObject = null;
            JSONArray secretsArray = new JSONArray();
            try {
                URL url = new URL("http://" + mVaultServerIp + ":" + mVaultServerIpPort + "/v1/" + folderPath + "?list=true");

                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.setRequestProperty("X-Vault-Token", mVaultToken);

                InputStream in = new BufferedInputStream(urlConnection.getInputStream());
                jsonObject = new JSONObject(convertInputStreamToString(in));

                secretsArray = jsonObject.getJSONObject("data").getJSONArray("keys");
            } catch (IOException | JSONException e) {
                Log.e(this.getClass().toString(), e.getMessage());
            } finally {
                if (urlConnection != null)
                    urlConnection.disconnect();
            }

            return secretsArray;
        }

        private void fillArray(JSONArray secretsArray, String parentFolderPath) {
            String value = "";

            for (int i = 0; i < secretsArray.length(); i++) {
                try {
                    value = (String) secretsArray.get(i);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                if (value.charAt(value.length() - 1) == '/') {
                    JSONArray folderContent;

                    String fullPath;
                    if (!parentFolderPath.isEmpty()) {
                        fullPath = parentFolderPath + "/" + value.substring(0, value.length() - 1);
                    } else {
                        fullPath = value.substring(0, value.length() - 1);
                    }

                    folderContent = getSecrets(fullPath);

                    fillArray(folderContent, fullPath);

                    try {
                        JSONObject holder = new JSONObject();
                        holder.put(value, folderContent);
                        secretsArray.put(i, holder);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        @Override
        protected JSONArray doInBackground(String... params) {
            JSONArray secretsArray;
            secretsArray = getSecrets("secret");

            fillArray(secretsArray, "secret");

            return secretsArray;
        }

        protected void onPostExecute(JSONArray secrets) {
            Log.d(this.getClass().toString(), "Passing secrets: " + secrets.toString());
            ArrayList<ListParentClass> listItems = constructListItemsTree(secrets);
            mListItems = listItems;
            // TODO: convert listItems to something simple using getListItemsStrings()
            mListViewAdapter.updateParents(listItems);
        }

        private String convertInputStreamToString(final InputStream is) {
            final int bufferSize = 1024;
            final char[] buffer = new char[bufferSize];
            final StringBuilder out = new StringBuilder();
            try (Reader in = new InputStreamReader(is, "UTF-8")) {
                for (; ; ) {
                    int rsz = in.read(buffer, 0, buffer.length);
                    if (rsz < 0)
                        break;
                    out.append(buffer, 0, rsz);
                }
            } catch (IOException e) {
                Log.e(this.getClass().toString(), e.getMessage());
            }
            return out.toString();
        }
    }
}


