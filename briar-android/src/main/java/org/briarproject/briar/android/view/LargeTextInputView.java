package org.briarproject.briar.android.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.support.annotation.UiThread;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;

import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.briar.R;

import javax.annotation.Nullable;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

@UiThread
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class LargeTextInputView extends TextInputView {

	public LargeTextInputView(Context context) {
		this(context, null);
	}

	public LargeTextInputView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public LargeTextInputView(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	protected void inflateLayout(Context context) {
		LayoutInflater inflater = (LayoutInflater) context
				.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		inflater.inflate(R.layout.text_input_view_large, this, true);
	}

	@Override
	protected void setUpViews(Context context, @Nullable AttributeSet attrs) {
		super.setUpViews(context, attrs);

		// get attributes
		TypedArray attributes = context.obtainStyledAttributes(attrs,
				R.styleable.LargeTextInputView);
		String buttonText =
				attributes.getString(R.styleable.LargeTextInputView_buttonText);
		int maxLines = attributes
				.getInteger(R.styleable.LargeTextInputView_maxLines, 0);
		boolean fillHeight = attributes
				.getBoolean(R.styleable.LargeTextInputView_fillHeight, false);
		attributes.recycle();

		if (buttonText != null) setButtonText(buttonText);
		if (maxLines > 0) editText.setMaxLines(maxLines);
		if (fillHeight) {
			ViewGroup layout = findViewById(R.id.input_layout);
			LayoutParams params = (LayoutParams) layout.getLayoutParams();
			params.height = 0;
			params.weight = 1;
			layout.setLayoutParams(params);
			ViewGroup.LayoutParams editParams = editText.getLayoutParams();
			editParams.height = MATCH_PARENT;
			editText.setLayoutParams(editParams);
		}
	}

	public void setButtonText(String text) {
		((Button) sendButton).setText(text);
	}

}
