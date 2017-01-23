/**
 * Copyright 2013 Google Inc. All Rights Reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.totschnig.myexpenses.activity;

import android.content.Intent;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveId;
import com.google.android.gms.drive.Metadata;
import com.google.android.gms.drive.OpenFileActivityBuilder;

import org.totschnig.myexpenses.R;
import org.totschnig.myexpenses.sync.GoogleDriveBackendProvider;
import org.totschnig.myexpenses.sync.GoogleDriveBackendProviderFactory;

import static org.totschnig.myexpenses.sync.GenericAccountService.KEY_SYNC_PROVIDER_URL;

public class DriveSetupActivity extends ProtectedFragmentActivity implements
    GoogleApiClient.ConnectionCallbacks,
    GoogleApiClient.OnConnectionFailedListener {

  private static final String TAG = DriveSetupActivity.class.getSimpleName();

  /**
   * Request code for auto Google Play Services error resolution.
   */
  protected static final int REQUEST_CODE_RESOLUTION = 1;


  private static final int REQUEST_CODE_OPENER = 2;

  /**
   * Google API client.
   */
  private GoogleApiClient mGoogleApiClient;
  private DriveFolder driveFolder;

  /**
   * Called when activity gets visible. A connection to Drive services need to
   * be initiated as soon as the activity is visible. Registers
   * {@code ConnectionCallbacks} and {@code OnConnectionFailedListener} on the
   * activities itself.
   */
  @Override
  protected void onResume() {
    super.onResume();
    if (mGoogleApiClient == null) {
      mGoogleApiClient = new GoogleApiClient.Builder(this)
          .addApi(Drive.API)
          .addScope(Drive.SCOPE_FILE)
          .addConnectionCallbacks(this)
          .addOnConnectionFailedListener(this)
          .build();
    }
    mGoogleApiClient.connect();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    switch (requestCode) {
      case REQUEST_CODE_OPENER:
        if (resultCode == RESULT_OK) {
          DriveId driveId = data.getParcelableExtra(
              OpenFileActivityBuilder.EXTRA_RESPONSE_DRIVE_ID);
          driveFolder = driveId.asDriveFolder();
        } else {
          setResult(RESULT_CANCELED);
          finish();
        }
        break;
      case REQUEST_CODE_RESOLUTION:
        if (resultCode == RESULT_OK) {
          mGoogleApiClient.connect();
        }
        break;
      default:
        super.onActivityResult(requestCode, resultCode, data);
        break;
    }
  }

  /**
   * Called when activity gets invisible. Connection to Drive service needs to
   * be disconnected as soon as an activity is invisible.
   */
  @Override
  protected void onPause() {
    if (mGoogleApiClient != null) {
      mGoogleApiClient.disconnect();
    }
    super.onPause();
  }

  /**
   * Called when {@code mGoogleApiClient} is connected.
   */
  @Override
  public void onConnected(Bundle connectionHint) {
    Log.i(TAG, "GoogleApiClient connected");
    if (driveFolder == null) {
      IntentSender intentSender = Drive.DriveApi
          .newOpenFileActivityBuilder()
          .setMimeType(new String[]{DriveFolder.MIME_TYPE})
          .build(getGoogleApiClient());
      try {
        startIntentSenderForResult(
            intentSender, REQUEST_CODE_OPENER, null, 0, 0, 0);
      } catch (SendIntentException e) {
        Log.w(TAG, "Unable to send intent", e);
      }
    } else {
      driveFolder.getMetadata(getGoogleApiClient())
          .setResultCallback(result -> {
            if (result.getStatus().isSuccess()) {
              Metadata metadata = result.getMetadata();
              if (metadata.getCustomProperties().containsKey(GoogleDriveBackendProvider.ACCOUNT_METADATA_UUID_KEY)) {
                showMessage(getString(R.string.warning_synchronization_select_parent));
                finish();
              } else {
                Bundle bundle = new Bundle(1);
                bundle.putString(KEY_SYNC_PROVIDER_URL, metadata.getDriveId().getResourceId());
                createAccount(GoogleDriveBackendProviderFactory.LABEL + " - " + metadata.getTitle(),
                    null, bundle);
              }

            } else {
              showMessage("Problem while trying to fetch metadata");
              finish();
            }
          });
    }
  }

  @Override
  public void onPostExecute(int taskId, Object o) {
    super.onPostExecute(taskId, o);
    setResult(RESULT_OK);
    finish();
  }

  /**
   * Called when {@code mGoogleApiClient} is disconnected.
   */
  @Override
  public void onConnectionSuspended(int cause) {
    Log.i(TAG, "GoogleApiClient connection suspended");
  }

  /**
   * Called when {@code mGoogleApiClient} is trying to connect but failed.
   * Handle {@code result.getResolution()} if there is a resolution is
   * available.
   */
  @Override
  public void onConnectionFailed(@NonNull ConnectionResult result) {
    Log.i(TAG, "GoogleApiClient connection failed: " + result.toString());
    if (!result.hasResolution()) {
      // show the localized error dialog.
      GoogleApiAvailability.getInstance().getErrorDialog(this, result.getErrorCode(), 0).show();
      return;
    }
    try {
      result.startResolutionForResult(this, REQUEST_CODE_RESOLUTION);
    } catch (SendIntentException e) {
      Log.e(TAG, "Exception while starting resolution activity", e);
    }
  }

  /**
   * Shows a toast message.
   */
  public void showMessage(String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  /**
   * Getter for the {@code GoogleApiClient}.
   */
  public GoogleApiClient getGoogleApiClient() {
    return mGoogleApiClient;
  }
}
