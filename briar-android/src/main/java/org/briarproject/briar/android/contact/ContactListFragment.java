package org.briarproject.briar.android.contact;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.util.Pair;
import android.support.v7.widget.LinearLayoutManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.contact.event.ContactRemovedEvent;
import org.briarproject.bramble.api.contact.event.ContactStatusChangedEvent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchContactException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.bramble.api.plugin.ConnectionRegistry;
import org.briarproject.bramble.api.plugin.event.ContactConnectedEvent;
import org.briarproject.bramble.api.plugin.event.ContactDisconnectedEvent;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.contact.BaseContactListAdapter.OnContactClickListener;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.keyagreement.ContactExchangeActivity;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.briar.api.client.MessageTracker.GroupCount;
import org.briarproject.briar.api.messaging.ConversationManager;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.briar.api.messaging.event.PrivateMessageReceivedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static android.os.Build.VERSION.SDK_INT;
import static android.support.v4.app.ActivityOptionsCompat.makeSceneTransitionAnimation;
import static android.support.v4.view.ViewCompat.getTransitionName;
import static java.util.logging.Level.WARNING;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.LogUtils.now;
import static org.briarproject.briar.android.contact.ConversationActivity.CONTACT_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ContactListFragment extends BaseFragment implements EventListener {

	public static final String TAG = ContactListFragment.class.getName();
	private static final Logger LOG = Logger.getLogger(TAG);

	@Inject
	ConnectionRegistry connectionRegistry;
	@Inject
	EventBus eventBus;
	@Inject
	AndroidNotificationManager notificationManager;

	private ContactListAdapter adapter;
	private BriarRecyclerView list;

	// Fields that are accessed from background threads must be volatile
	@Inject
	volatile ContactManager contactManager;
	@Inject
	volatile ConversationManager conversationManager;

	public static ContactListFragment newInstance() {
		Bundle args = new Bundle();
		ContactListFragment fragment = new ContactListFragment();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {

		getActivity().setTitle(R.string.contact_list_button);

		View contentView = inflater.inflate(R.layout.list, container, false);

		OnContactClickListener<ContactListItem> onContactClickListener =
				(view, item) -> {
					Intent i = new Intent(getActivity(),
							ConversationActivity.class);
					ContactId contactId = item.getContact().getId();
					i.putExtra(CONTACT_ID, contactId.getInt());

					if (SDK_INT >= 23) {
						ContactListItemViewHolder holder =
								(ContactListItemViewHolder) list
										.getRecyclerView()
										.findViewHolderForAdapterPosition(
												adapter.findItemPosition(item));
						Pair<View, String> avatar =
								Pair.create(holder.avatar,
										getTransitionName(holder.avatar));
						Pair<View, String> bulb =
								Pair.create(holder.bulb,
										getTransitionName(holder.bulb));
						ActivityOptionsCompat options =
								makeSceneTransitionAnimation(getActivity(),
										avatar, bulb);
						ActivityCompat.startActivity(getActivity(), i,
								options.toBundle());
					} else {
						// work-around for android bug #224270
						startActivity(i);
					}
				};
		adapter = new ContactListAdapter(getContext(), onContactClickListener);
		list = contentView.findViewById(R.id.list);
		list.setLayoutManager(new LinearLayoutManager(getContext()));
		list.setAdapter(adapter);
		list.setEmptyImage(R.drawable.ic_empty_state_contact_list);
		list.setEmptyText(getString(R.string.no_contacts));
		list.setEmptyAction(getString(R.string.no_contacts_action));

		return contentView;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.contact_list_actions, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle presses on the action bar items
		switch (item.getItemId()) {
			case R.id.action_add_contact:
				Intent intent =
						new Intent(getContext(), ContactExchangeActivity.class);
				startActivity(intent);
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		notificationManager.clearAllContactNotifications();
		notificationManager.clearAllIntroductionNotifications();
		loadContacts();
		list.startPeriodicUpdate();
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(this);
		adapter.clear();
		list.showProgressBar();
		list.stopPeriodicUpdate();
	}

	private void loadContacts() {
		int revision = adapter.getRevision();
		listener.runOnDbThread(() -> {
			try {
				long start = now();
				List<ContactListItem> contacts = new ArrayList<>();
				for (Contact c : contactManager.getActiveContacts()) {
					try {
						ContactId id = c.getId();
						GroupCount count =
								conversationManager.getGroupCount(id);
						boolean connected =
								connectionRegistry.isConnected(c.getId());
						contacts.add(new ContactListItem(c, connected, count));
					} catch (NoSuchContactException e) {
						// Continue
					}
				}
				logDuration(LOG, "Full load", start);
				displayContacts(revision, contacts);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}

	private void displayContacts(int revision, List<ContactListItem> contacts) {
		runOnUiThreadUnlessDestroyed(() -> {
			if (revision == adapter.getRevision()) {
				adapter.incrementRevision();
				if (contacts.isEmpty()) list.showData();
				else adapter.addAll(contacts);
			} else {
				LOG.info("Concurrent update, reloading");
				loadContacts();
			}
		});
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactStatusChangedEvent) {
			ContactStatusChangedEvent c = (ContactStatusChangedEvent) e;
			if (c.isActive()) {
				LOG.info("Contact activated, reloading");
				loadContacts();
			} else {
				LOG.info("Contact deactivated, removing item");
				removeItem(c.getContactId());
			}
		} else if (e instanceof ContactConnectedEvent) {
			setConnected(((ContactConnectedEvent) e).getContactId(), true);
		} else if (e instanceof ContactDisconnectedEvent) {
			setConnected(((ContactDisconnectedEvent) e).getContactId(), false);
		} else if (e instanceof ContactRemovedEvent) {
			LOG.info("Contact removed, removing item");
			removeItem(((ContactRemovedEvent) e).getContactId());
		} else if (e instanceof PrivateMessageReceivedEvent) {
			LOG.info("Private message received, updating item");
			PrivateMessageReceivedEvent p = (PrivateMessageReceivedEvent) e;
			PrivateMessageHeader h = p.getMessageHeader();
			updateItem(p.getContactId(), h);
		}
	}

	private void updateItem(ContactId c, PrivateMessageHeader h) {
		runOnUiThreadUnlessDestroyed(() -> {
			adapter.incrementRevision();
			int position = adapter.findItemPosition(c);
			ContactListItem item = adapter.getItemAt(position);
			if (item != null) {
				item.addMessage(h);
				adapter.updateItemAt(position, item);
			}
		});
	}

	private void removeItem(ContactId c) {
		runOnUiThreadUnlessDestroyed(() -> {
			adapter.incrementRevision();
			int position = adapter.findItemPosition(c);
			ContactListItem item = adapter.getItemAt(position);
			if (item != null) adapter.remove(item);
		});
	}

	private void setConnected(ContactId c, boolean connected) {
		runOnUiThreadUnlessDestroyed(() -> {
			adapter.incrementRevision();
			int position = adapter.findItemPosition(c);
			ContactListItem item = adapter.getItemAt(position);
			if (item != null) {
				item.setConnected(connected);
				adapter.notifyItemChanged(position);
			}
		});
	}

}
