package com.ppmessage.ppcomlib.ui;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.view.MenuItem;
import android.view.View;

import com.ppmessage.ppcomlib.PPComSDK;
import com.ppmessage.ppcomlib.PPComSDKException;
import com.ppmessage.ppcomlib.R;
import com.ppmessage.ppcomlib.model.ConversationsModel;
import com.ppmessage.ppcomlib.services.ConversationWaitingService;
import com.ppmessage.ppcomlib.services.PPComStartupHelper;
import com.ppmessage.ppcomlib.utils.PPComUtils;
import com.ppmessage.sdk.core.L;
import com.ppmessage.sdk.core.PPMessageSDK;
import com.ppmessage.sdk.core.bean.common.Conversation;
import com.ppmessage.sdk.core.bean.message.PPMessage;
import com.ppmessage.sdk.core.model.UnackedMessagesLoader;
import com.ppmessage.sdk.core.notification.INotification;
import com.ppmessage.sdk.core.notification.WSMessageAckNotificationHandler;
import com.ppmessage.sdk.core.ui.ConversationFragment;
import com.ppmessage.sdk.core.ui.adapter.ConversationsAdapter;
import com.ppmessage.sdk.core.utils.Utils;

import java.util.List;

/**
 * Created by ppmessage on 5/13/16.
 */
public class ConversationsActivity extends AppCompatActivity {

    protected ConversationFragment conversationFragment;
    private Dialog loadingDialog;

    private PPComSDK sdk;
    private PPMessageSDK messageSDK;
    private ConversationsModel conversationsModel;
    private UnackedMessagesLoader unackedMessagesLoader;
    private ConversationWaitingService conversationWaitingService;

    private boolean inWaiting;

    private INotification.OnNotificationEvent notificationListener;

    private static final String LOG_WAITING = "[ConversationsActivity] waiting conversations ...";
    private static final String LOG_REMOVE_LISTENER = "[ConversationsActivity] remove notification listener";
    private static final String LOG_ADD_LISTENER = "[ConversationsActivity] add notification listener";
    private static final String LOG_CANCEL_ANY_ONGOING_TASK = "[ConversationsActivity] cancel any ongoing task";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PPComUtils.setActivityActionBarStyle(this);

        sdk = PPComSDK.getInstance();
        messageSDK = sdk.getPPMessageSDK();
        conversationWaitingService = new ConversationWaitingService(sdk);
        conversationsModel = sdk.getMessageService().getConversationsModel();
        unackedMessagesLoader = new UnackedMessagesLoader(messageSDK);

        conversationFragment = getConversationFragment();
        setFragment(conversationFragment);
    }

    @Override
    protected void onResume() {
        super.onResume();

        conversationFragment.setOnItemClickListener(new ConversationsAdapter.OnItemClickListener() {
            @Override
            public void onItemClicked(final View container, final Conversation conversation) {
                startMessageActivity(conversation);
            }
        });

        innerResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        removeNotificationListener();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelAnyOnGoingTask();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        cancelAnyOnGoingTask();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void startUp() {
        getLoadingDialog().show();

        sdk.getStartupHelper().startUp(new PPComStartupHelper.OnStartupCallback() {
            @Override
            public void onSuccess() {
                L.d("=== startup success ===");
                ConversationsActivity.this.onStartupSuccess();
            }

            @Override
            public void onError(PPComSDKException exception) {
                L.e("=== startup error: %s ===", exception);
                Utils.makeToast(ConversationsActivity.this, R.string.pp_com_sdk_startup_error);
            }
        });
    }

    /**
     * Override this methods, to provided your own implemented ConversationFragment
     *
     * @return
     */
    protected ConversationFragment getConversationFragment() {
        ConversationFragment fragment = new ConversationFragment();
        fragment.setMessageSDK(sdk.getPPMessageSDK());
        return fragment;
    }

    /**
     * Set conversation list to update Conversations content
     *
     * @param conversationList
     */
    public void setConversationList(List<Conversation> conversationList) {
        if (conversationFragment != null) {
            conversationFragment.setConversationList(conversationList);
        }
    }

    protected Dialog getLoadingDialog() {
        if (loadingDialog == null) {
            ProgressDialog progressDialog = new ProgressDialog(this);
            progressDialog.setTitle(R.string.pp_com_sdk_loading_dialog_content);
            progressDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    cancelWaiting();
                }
            });

            loadingDialog = progressDialog;
        }
        return loadingDialog;
    }

    private void innerResume() {
        notifyDataSetChanged();
        addNotificationListener();
    }

    private void setFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
    }

    private void onStartupSuccess() {
        conversationsModel.asyncGetConversations(new ConversationsModel.OnGetConversationsEvent() {
            @Override
            public void onCompleted(List<Conversation> conversationList) {
                if (conversationList != null) {
                    setConversationList(conversationsModel.sortedConversations());
                    loadUnackedMessages();
                    getLoadingDialog().dismiss();
                } else {
                    waiting();
                }
            }
        });
    }

    private void waiting() {
        conversationWaitingService.start(new ConversationWaitingService.OnConversationReadyCallback() {
            @Override
            public void ready(Conversation conversation) {
                sdk.getMessageService().getConversationsModel().add(conversation);
                notifyDataSetChanged();
                getLoadingDialog().dismiss();
                inWaiting = false;
            }
        });
        inWaiting = true;
        L.d(LOG_WAITING);
    }

    private void addNotificationListener() {
        L.d(LOG_ADD_LISTENER);

        INotification notification = sdk.getPPMessageSDK().getNotification();
        notificationListener = new INotification.SimpleNotificationEvent() {

            @Override
            public int getInterestedEvent() {
                return INotification.EVENT_MESSAGE | INotification.EVENT_MSG_SEND_ERROR;
            }

            @Override
            public void onMessageInfoArrived(PPMessage message) {
                sdk.getMessageService().updateModels(message);
                notifyDataSetChanged();
            }

            @Override
            public void onMessageSendError(WSMessageAckNotificationHandler.MessageSendResult messageSendResult) {
                PPMessage find = sdk.getMessageService().getMessagesModel()
                        .findMessage(messageSendResult.getConversationUUID(),
                                messageSendResult.getMessageUUID());
                if (find != null) {
                    find.setError(true);
                }
            }

        };
        notification.addListener(notificationListener);
    }

    private void removeNotificationListener() {
        L.d(LOG_REMOVE_LISTENER);

        if (notificationListener != null) {
            sdk.getPPMessageSDK().getNotification().removeListener(notificationListener);
            notificationListener = null;
        }
    }

    private void notifyDataSetChanged() {
        setConversationList(conversationsModel.sortedConversations());
    }

    private void cancelWaiting() {
        getLoadingDialog().dismiss();

        if (!inWaiting) return;

        inWaiting = false;
        if (conversationWaitingService != null) {
            conversationWaitingService.cancel();
        }
    }

    private void startMessageActivity(Conversation conversation) {
        if (conversation == null || conversation.getConversationUUID() == null) return;

        Intent intent = new Intent(ConversationsActivity.this, PPComMessageActivity.class);
        intent.putExtra(PPComMessageActivity.EXTRA_KEY_CONVERSATION_UUID, conversation.getConversationUUID());
        intent.putExtra(PPComMessageActivity.EXTRA_KEY_CONVERSATION_NAME, conversation.getConversationName());
        startActivity(intent);
    }

    private void loadUnackedMessages() {
        if (unackedMessagesLoader != null) {
            unackedMessagesLoader.loadUnackedMessages();
        }
    }

    private void cancelAnyOnGoingTask() {
        cancelWaiting();
        if (unackedMessagesLoader != null) {
            unackedMessagesLoader.stop();
            unackedMessagesLoader = null;
        }
        if (sdk != null) {
            sdk.getStartupHelper().shutdown();
        }
        L.d(LOG_CANCEL_ANY_ONGOING_TASK);
    }

}
