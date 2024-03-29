package org.briarproject.briar.android.introduction;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.ActionBar;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.view.TextInputView;
import org.briarproject.briar.android.view.TextInputView.TextInputListener;
import org.briarproject.briar.api.introduction.IntroductionManager;

import java.util.logging.Logger;

import javax.inject.Inject;

import de.hdodenhof.circleimageview.CircleImageView;
import im.delight.android.identicons.IdenticonDrawable;

import static android.app.Activity.RESULT_OK;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.logging.Level.WARNING;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.briar.api.introduction.IntroductionConstants.MAX_INTRODUCTION_TEXT_LENGTH;

public class IntroductionMessageFragment extends BaseFragment
		implements TextInputListener {

	public static final String TAG =
			IntroductionMessageFragment.class.getName();
	private static final Logger LOG = Logger.getLogger(TAG);

	private final static String CONTACT_ID_1 = "contact1";
	private final static String CONTACT_ID_2 = "contact2";

	private IntroductionActivity introductionActivity;
	private ViewHolder ui;
	private Contact contact1, contact2;

	// Fields that are accessed from background threads must be volatile
	@Inject
	protected volatile ContactManager contactManager;
	@Inject
	protected volatile IntroductionManager introductionManager;

	public static IntroductionMessageFragment newInstance(int contactId1,
			int contactId2) {
		Bundle args = new Bundle();
		args.putInt(CONTACT_ID_1, contactId1);
		args.putInt(CONTACT_ID_2, contactId2);
		IntroductionMessageFragment fragment =
				new IntroductionMessageFragment();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		introductionActivity = (IntroductionActivity) context;
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			ViewGroup container, Bundle savedInstanceState) {

		// change toolbar text
		ActionBar actionBar = introductionActivity.getSupportActionBar();
		if (actionBar != null) {
			actionBar.setTitle(R.string.introduction_message_title);
		}

		// inflate view
		View v = inflater.inflate(R.layout.introduction_message, container,
				false);
		ui = new ViewHolder(v);
		ui.message.setSendButtonEnabled(false);

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();

		// get contact IDs from fragment arguments
		int contactId1 = getArguments().getInt(CONTACT_ID_1, -1);
		int contactId2 = getArguments().getInt(CONTACT_ID_2, -1);
		if (contactId1 == -1 || contactId2 == -1) {
			throw new java.lang.InstantiationError(
					"You need to use newInstance() to instantiate");
		}
		// get contacts and then show view
		prepareToSetUpViews(contactId1, contactId2);
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	private void prepareToSetUpViews(int contactId1, int contactId2) {
		introductionActivity.runOnDbThread(() -> {
			try {
				Contact c1 = contactManager.getContact(
						new ContactId(contactId1));
				Contact c2 = contactManager.getContact(
						new ContactId(contactId2));
				boolean possible = introductionManager.canIntroduce(c1, c2);
				setUpViews(c1, c2, possible);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
		});
	}

	private void setUpViews(Contact c1, Contact c2, boolean possible) {
		introductionActivity.runOnUiThreadUnlessDestroyed(() -> {
			contact1 = c1;
			contact2 = c2;

			// set avatars
			ui.avatar1.setImageDrawable(new IdenticonDrawable(
					c1.getAuthor().getId().getBytes()));
			ui.avatar2.setImageDrawable(new IdenticonDrawable(
					c2.getAuthor().getId().getBytes()));

			// set contact names
			ui.contactName1.setText(c1.getAuthor().getName());
			ui.contactName2.setText(c2.getAuthor().getName());

			// hide progress bar
			ui.progressBar.setVisibility(GONE);

			if (possible) {
				// set button action
				ui.message.setListener(IntroductionMessageFragment.this);

				// show views
				ui.notPossible.setVisibility(GONE);
				ui.message.setVisibility(VISIBLE);
				ui.message.setSendButtonEnabled(true);
				ui.message.showSoftKeyboard();
			} else {
				ui.notPossible.setVisibility(VISIBLE);
				ui.message.setVisibility(GONE);
			}
		});
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				introductionActivity.hideSoftKeyboard(ui.message);
				introductionActivity.onBackPressed();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onSendClick(@NonNull String text) {
		// disable button to prevent accidental double invitations
		ui.message.setSendButtonEnabled(false);

		String txt = ui.message.getText().toString();
		if (txt.isEmpty()) txt = null;
		else txt = StringUtils.truncateUtf8(txt, MAX_INTRODUCTION_TEXT_LENGTH);
		makeIntroduction(contact1, contact2, txt);

		// don't wait for the introduction to be made before finishing activity
		introductionActivity.hideSoftKeyboard(ui.message);
		introductionActivity.setResult(RESULT_OK);
		introductionActivity.supportFinishAfterTransition();
	}

	private void makeIntroduction(Contact c1, Contact c2,
			@Nullable String text) {
		introductionActivity.runOnDbThread(() -> {
			// actually make the introduction
			try {
				long timestamp = System.currentTimeMillis();
				introductionManager.makeIntroduction(c1, c2, text, timestamp);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				introductionError();
			}
		});
	}

	private void introductionError() {
		introductionActivity.runOnUiThreadUnlessDestroyed(
				() -> Toast.makeText(introductionActivity,
						R.string.introduction_error, LENGTH_SHORT).show());
	}

	private static class ViewHolder {

		private final ProgressBar progressBar;
		private final CircleImageView avatar1, avatar2;
		private final TextView contactName1, contactName2;
		private final TextView notPossible;
		private final TextInputView message;

		private ViewHolder(View v) {
			progressBar = v.findViewById(R.id.progressBar);
			avatar1 = v.findViewById(R.id.avatarContact1);
			avatar2 = v.findViewById(R.id.avatarContact2);
			contactName1 = v.findViewById(R.id.nameContact1);
			contactName2 = v.findViewById(R.id.nameContact2);
			notPossible = v.findViewById(R.id.introductionNotPossibleView);
			message = v.findViewById(R.id.introductionMessageView);
		}
	}
}
