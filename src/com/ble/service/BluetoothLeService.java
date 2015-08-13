/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ble.service;

import java.util.List;


import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

/**
 * Service for managing connection and data communication with a GATT server
 * hosted on a given Bluetooth LE device.
 */
@SuppressLint("NewApi")
public class BluetoothLeService extends Service {
	private final static String TAG = BluetoothLeService.class.getSimpleName();

	private BluetoothManager mBluetoothManager;
	private BluetoothAdapter mBluetoothAdapter;
	private String mBluetoothDeviceAddress;
	private BluetoothGatt mBluetoothGatt;

	private boolean isFirstSend = true;

	//是否连接
	public final static String ACTION_GATT_CONNECTED = "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
	//是否断开
	public final static String ACTION_GATT_DISCONNECTED = "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
	//发现服务
	public final static String ACTION_GATT_SERVICES_DISCOVERED = "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
	//收到数据
	public final static String ACTION_DATA_AVAILABLE = "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
	//传递数据的标志位：2表示在检测中，有数据在接收对应蓝灯提示；3表示没有检测成功，对应红灯提示；xxx表示真实数据
	public final static String EXTRA_DATA = "com.example.bluetooth.le.EXTRA_DATA";
	//电量
	public final static String POWER_DATA = "com.example.bluetooth.le.POWER_DATA";

	private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status,
				int newState) {
			String intentAction;
			if (newState == BluetoothProfile.STATE_CONNECTED) {
				intentAction = ACTION_GATT_CONNECTED;
				broadcastUpdate(intentAction);

				mBluetoothGatt.discoverServices();

			} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				intentAction = ACTION_GATT_DISCONNECTED;
				broadcastUpdate(intentAction);
			}
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {

			if (status == BluetoothGatt.GATT_SUCCESS) {
				broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
			}
		}

		@Override
		public void onCharacteristicRead(BluetoothGatt gatt,
				BluetoothGattCharacteristic characteristic, int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
			}
		}

		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
		}

		@Override
		public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
		}

		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
		};

	};

	private void broadcastUpdate(final String action) {
		final Intent intent = new Intent(action);
		sendBroadcast(intent);
	}

	private void broadcastUpdate(final String action, final BluetoothGattCharacteristic characteristic) {
		final Intent intent = new Intent(action);

		// For all other profiles, writes the data formatted in HEX.
		final byte[] data = characteristic.getValue();

		if (data != null && data.length > 0) {
			final StringBuilder stringBuilder = new StringBuilder(data.length);
			for (byte byteChar : data) {
				stringBuilder.append(String.format("%02X ", byteChar)); //转换成16进制并且在前面补0两个
			}
			String s = "0", s1 = "0";
			int num = 0; // 电量
			int num1 = 0; // 数据值
			if (stringBuilder.toString().length() >= 20) { // 加密的数据处理
				s = stringBuilder.toString().substring(0, 2);
				s1 = stringBuilder.toString().substring(3, 5);
				int a = Integer.parseInt(s.trim(), 16);
				int b = Integer.parseInt(s1.trim(), 16);
				int c = a ^ b;

				String s2 = stringBuilder.toString().substring(6, 8); // 第二个随机数
				int p = Integer.parseInt(s2.trim(), 16); // 第二个随机数对应的int
				int q = p & 7; // 循环左移的位数
				p = ((p << q) % 256) | (p >> (8 - q));
				if (p < 0)
					p += 256; // 避免移位后出现负值
				if (c == 202) { // 控制指令0xca
					s1 = stringBuilder.toString().substring(9, 11);
					b = Integer.parseInt(s1.trim(), 16);
					c = p ^ b;
					if (c == 85) { // 2
						num1 = 2;
					} else if (c == 225) { // 3
						num1 = 3;
					}
				} else if (c == 197) { // 电量和水分值0xc5
					s1 = stringBuilder.toString().substring(9, 11);
					int dd1 = Integer.parseInt(s1.trim(), 16);

					s1 = stringBuilder.toString().substring(12, 14);
					int dd2 = Integer.parseInt(s1.trim(), 16);

					s1 = stringBuilder.toString().substring(15, 17);
					int dd3 = Integer.parseInt(s1.trim(), 16);
					int d3 = dd2 ^ dd3;
					s1 = stringBuilder.toString().substring(18, 20);
					int dd4 = Integer.parseInt(s1.trim(), 16);
					int d4 = dd3 ^ dd4;
					if (isFirstSend) {
						int d1 = p ^ dd1;
						int d2 = dd1 ^ dd2;
						num = d1 + d2 * 256;
					}
					num1 = d3 + d4 * 256;
				}
				// System.out.println(a^b);
			} else { // 未加密的数据处理
				if (isFirstSend && stringBuilder.toString().length() >= 11) {
					s = stringBuilder.toString().substring(9, 11)
							+ stringBuilder.toString().substring(6, 8);
					num = Integer.parseInt(s.trim(), 16);
				}
				if (stringBuilder.toString().length() >= 11)
					s1 = stringBuilder.toString().substring(3, 5)
							+ stringBuilder.toString().substring(0, 2);
				num1 = Integer.parseInt(s1.trim(), 16); // 数据值
			}


			// 广播数据，activity里得到这个数据
			if (isFirstSend && num > 0 && num1 != 0) {
				isFirstSend = false;
				intent.putExtra(POWER_DATA, num);
				intent.putExtra(EXTRA_DATA, num1);
			} else
				intent.putExtra(EXTRA_DATA, num1);
		}

		sendBroadcast(intent);
	}

	public class LocalBinder extends Binder {
		public BluetoothLeService getService() {
			return BluetoothLeService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		close();
		return super.onUnbind(intent);
	}

	private final IBinder mBinder = new LocalBinder();

	/**
	 * Initializes a reference to the local Bluetooth adapter.
	 * 
	 * @return Return true if the initialization is successful.
	 */
	public boolean initialize() {
		// For API level 18 and above, get a reference to BluetoothAdapter
		// through
		// BluetoothManager.
		if (mBluetoothManager == null) {
			mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
			if (mBluetoothManager == null) {
				return false;
			}
		}

		mBluetoothAdapter = mBluetoothManager.getAdapter();
		if (mBluetoothAdapter == null) {
			Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
			return false;
		}

		return true;
	}

	/**
	 * Connects to the GATT server hosted on the Bluetooth LE device.
	 * 
	 * @param address
	 *            The device address of the destination device.
	 * 
	 * @return Return true if the connection is initiated successfully. The
	 *         connection result is reported asynchronously through the
	 *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
	 *         callback.
	 */
	public boolean connect(final String address) {
	
		if (mBluetoothAdapter == null || address == null) {
			
			return false;
		}

		if (mBluetoothDeviceAddress != null
				&& address.equals(mBluetoothDeviceAddress)
				&& mBluetoothGatt != null) {
			
			if (mBluetoothGatt.connect()) {
				return true;
			} else {
				return false;
			}
		}
		final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
		if (device == null) {
			return false;
		}
		mBluetoothGatt = device.connectGatt(this, false, mGattCallback);

		mBluetoothDeviceAddress = address;
		Log.d("ActivityLifeText", "设备连接成功，请开始检测！");
		return true;
	}
 

	public void disconnect() {
		// TODO Auto-generated method stub
		mBluetoothGatt.disconnect();
	}

	/**
	 * After using a given BLE device, the app must call this method to ensure
	 * resources are released properly.
	 */
	public void close() {
		if (mBluetoothGatt == null) {
			return;
		}
		mBluetoothGatt.close();
		mBluetoothGatt = null;
	}

	/**
	 * Request a read on a given {@code BluetoothGattCharacteristic}. The read
	 * result is reported asynchronously through the
	 * {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
	 * callback.
	 * 
	 * @param characteristic
	 *            The characteristic to read from.
	 */
	public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			return;
		}
		mBluetoothGatt.readCharacteristic(characteristic);
	}

	public void wirteCharacteristic(BluetoothGattCharacteristic characteristic) {

		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			return;
		}

		mBluetoothGatt.writeCharacteristic(characteristic);

	}

	/**
	 * Enables or disables notification on a give characteristic.
	 * 
	 * @param characteristic
	 *            Characteristic to act on.
	 * @param enabled
	 *            If true, enable notification. False otherwise.
	 * 
	 *            public void setCharacteristicNotification(
	 *            BluetoothGattCharacteristic characteristic, boolean enabled) {
	 *            Log.d("XK", "setCharacteristicNotification"); if
	 *            (mBluetoothAdapter == null || mBluetoothGatt == null) {
	 *            Log.w(TAG, "BluetoothAdapter not initialized"); return; }
	 *            mBluetoothGatt.setCharacteristicNotification(characteristic,
	 *            enabled); BluetoothGattDescriptor descriptor =
	 *            characteristic.getDescriptor(UUID
	 *            .fromString(SampleGattAttributes0
	 *            .CLIENT_CHARACTERISTIC_CONFIG)); if (descriptor != null) {
	 *            System.out.println("write descriptor"); descriptor
	 *            .setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
	 *            mBluetoothGatt.writeDescriptor(descriptor); } /* // This is
	 *            specific to Heart Rate Measurement. if
	 *            (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid()))
	 *            { System
	 *            .out.println("characteristic.getUuid() == "+characteristic
	 *            .getUuid ()+", "); BluetoothGattDescriptor descriptor =
	 *            characteristic.getDescriptor
	 *            (UUID.fromString(SampleGattAttributes
	 *            .CLIENT_CHARACTERISTIC_CONFIG)); descriptor
	 *            .setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
	 *            mBluetoothGatt.writeDescriptor(descriptor); }
	 * 
	 *            }
	 */

	/**
	 * Retrieves a list of supported GATT services on the connected device. This
	 * should be invoked only after {@code BluetoothGatt#discoverServices()}
	 * completes successfully.
	 * 
	 * @return A {@code List} of supported services.
	 */
	public List<BluetoothGattService> getSupportedGattServices() {
		if (mBluetoothGatt == null)
			return null;

		return mBluetoothGatt.getServices();
	}

	/**
	 * Read the RSSI for a connected remote device.
	 * */
	public boolean getRssiVal() {
		if (mBluetoothGatt == null)
			return false;

		return mBluetoothGatt.readRemoteRssi();
	}
}
