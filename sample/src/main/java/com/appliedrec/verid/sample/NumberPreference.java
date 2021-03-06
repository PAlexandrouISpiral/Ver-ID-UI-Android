package com.appliedrec.verid.sample;

import android.content.Context;
import android.content.res.TypedArray;
import android.support.v7.preference.DialogPreference;
import android.util.AttributeSet;

public class NumberPreference extends DialogPreference {

    private int value;
    private int minValue = 0;
    private int maxValue = 100;

    public NumberPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setPositiveButtonText(android.R.string.ok);
        setNegativeButtonText(android.R.string.cancel);
        if (attrs != null) {
            for (int i=0; i<attrs.getAttributeCount(); i++) {
                String name = attrs.getAttributeName(i);
                String value = attrs.getAttributeValue(i);
                if ("minValue".equalsIgnoreCase(name)) {
                    minValue = Integer.parseInt(value);
                } else if ("maxValue".equalsIgnoreCase(name)) {
                    maxValue = Integer.parseInt(value);
                }
            }
        }
    }

    public NumberPreference(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NumberPreference(Context context) {
        this(context, null, 0);
    }

    @Override
    public int getDialogLayoutResource() {
        return R.layout.pref_number_picker;
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getInt(index, 0);
    }

    @Override
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
        setValue(restorePersistedValue ? getPersistedInt(value) : (int) defaultValue);
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        setSummary(Integer.toString(value));
        if (shouldPersist()) {
            persistInt(value);
        }
        if (value != this.value) {
            this.value = value;
            notifyChanged();
        }
    }

    public int getMinValue() {
        return minValue;
    }

    public int getMaxValue() {
        return maxValue;
    }
}
