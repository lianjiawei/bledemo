package com.activity;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.ble.service.BluetoothLeService;

@SuppressLint("NewApi")
public class DetectionActivity extends Activity {
	private TextView waterValue, readyTextView;
	private ListView lvdevices;

	// private BlueSingleton singleton;
	// 蓝牙
	// ble parm
	private final static String TAG = DetectionActivity.class.getSimpleName();
	public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
	public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
	private static final int REQUEST_ENABLE_BT_CLICK = 3;

	private String mDeviceAddress;
	private BluetoothLeService mBluetoothLeService;
	private BluetoothAdapter mBluetoothAdapter;

	private boolean received = false; // 是否已开始接受数据
	private boolean isServiceReg = false; // mServiceConnection是否已绑定
	private static boolean receiverReleased = false; // mGattUpdateReceiver是否已释放注册
	private boolean supportBLE;
	private boolean isNeedToScan=true;

	private double receivedWaterData = 0.0;

	private static long lasttime;
	private final int DEVICE_DIS_CONNECT = 103;
	private final int FIND_DIVECS = 24;
	private List<String> devicesNames = new ArrayList<String>();
	private List<String> devicesAddress = new ArrayList<String>();
	private ArrayAdapter<String> adapter;
	/*--------------------------------------------------------------------------------
	 * BLE
	 *--------------------------------------------------------------------------------
	 */
	Handler handler = new Handler() {
		@SuppressLint("HandlerLeak")
		public void handleMessage(Message msg)

		{
			switch (msg.what) {
			case DEVICE_DIS_CONNECT:

				if (receiverReleased) {
					registerReceiver(mGattUpdateReceiver,
							makeGattUpdateIntentFilter());
					receiverReleased = false;
				}
				if ((mBluetoothAdapter != null && !mBluetoothAdapter
						.isEnabled()) || mBluetoothAdapter == null) {
					readyTextView.setText(R.string.ble_detect_step_unstartble);
				} else {
					readyTextView.setText(R.string.ble_detect_step_search);
					scanLeDevice(true);
					Log.d("ActivityLifeText", "再次搜索");
				}
				break;
			case FIND_DIVECS:
				adapter.notifyDataSetChanged();
				if(isNeedToScan)
				scanLeDevice(true);
				break;
//			case 0:
//				devicesNames.clear();
//				adapter.notifyDataSetChanged();
//				scanLeDevice(true);
//				handler.sendEmptyMessageDelayed(0, 5000);
			}
		}
	};

	private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			System.out.println("action = " + action);
			if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
				readyTextView.setText(R.string.ble_detect_step_linked);
				mConnected = true;
			} else if (BluetoothLeService.ACTION_GATT_DISCONNECTED
					.equals(action)) {
				if (mConnected) {
					mConnected = false;
					if ((mBluetoothAdapter != null && !mBluetoothAdapter
							.isEnabled()) || mBluetoothAdapter == null) {
						readyTextView
								.setText(R.string.ble_detect_step_unstartble);
					} else {
						readyTextView.setText("断开连接");
						scanLeDevice(true);
					}

					// 快速断开重连
					if (mBluetoothLeService != null) {
						final boolean result = mBluetoothLeService
								.connect(mDeviceAddress);
					}

					waterValue.setText("0");

				}
				// clearUI();
			} else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED
					.equals(action)) {
				readyTextView.setText(R.string.ble_detect_step_linked);
				mConnected = true;
				lasttime = new Date().getTime();
				Thread thread = new Thread(new Runnable() {
					@Override
					public void run() {
						while (mConnected) {
							if (mBluetoothLeService == null) {
								return;
							}
							final BluetoothGattCharacteristic characteristic = getCharacteristic(mBluetoothLeService
									.getSupportedGattServices());
							if (characteristic != null) {
								final int charaProp = characteristic
										.getProperties();
								if ((charaProp & BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
									mBluetoothLeService
											.readCharacteristic(characteristic);
								}
							}
							long now = new Date().getTime();
							if (now - lasttime > 30000) { // 超过60s没数据自动断开
								mConnected = false;
								try {
									if (!receiverReleased) {
										receiverReleased = true;
										unregisterReceiver(mGattUpdateReceiver);
									}
									if (isServiceReg
											&& mServiceConnection != null) {
										isServiceReg = false;
										unbindService(mServiceConnection);
									}

									Message message = new Message();
									message.what = FIND_DIVECS;
									handler.sendMessage(message);
								} catch (Exception e) {
									e.printStackTrace();
								}
							}

							try {
								Thread.sleep(600);
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}

						}
					}
				});
				thread.start();

			} else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {

				int data = intent.getIntExtra(BluetoothLeService.EXTRA_DATA, 0);
				if (!received && data == 2) {
					received = true;
					waterValue.setText("0");
					readyTextView.setText(R.string.ble_detect_step_detecting);
				}
				if (received && data == 3) {
					received = false;
				} else if (received && data != 0 && data != 2 && data != 3) {
					received = false;
					if (data <= 100) { // 值太小重测
					} else if (data > 100 && data < 834) {
						receivedWaterData = (data + 295) * 0.053125;
						waterValue.setText("" + receivedWaterData);
					}
				}
			}
		}
	};

	private BluetoothGattCharacteristic getCharacteristic(
			List<BluetoothGattService> gattServices) {
		if (gattServices == null)
			return null;
		for (BluetoothGattService gattService : gattServices) {
			String suuid = gattService.getUuid().toString();
			if (suuid.substring(4, 8).endsWith("1600")) {
				return gattService.getCharacteristic(UUID
						.fromString("00001601-0000-1000-8000-00805f9b34fb"));
			}
		}
		return null;
	}

	private static IntentFilter makeGattUpdateIntentFilter() {
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
		intentFilter
				.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
		intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
		return intentFilter;
	}

	@Override
	protected void onStart() {
		super.onStart();
		Log.d("ActivityLifeText", "onStart");
		if (supportBLE) {
			begainBindService();
			registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
			receiverReleased = false;
		}

	}

	protected void onResume() {
		// TODO Auto-generated method stub
		super.onResume();
		Log.d("ActivityLifeText", "onResume");
		// 再次进入界面后判断是否要重新搜索理解Activity的生命周期由onCreate()-->到onStart()-->到onResume()
		if (supportBLE) {
			if ((mBluetoothAdapter != null && !mBluetoothAdapter.isEnabled())
					|| mBluetoothAdapter == null) {
				readyTextView.setText(R.string.ble_detect_step_unstartble);
			} else {
				if (mBluetoothAdapter.isEnabled() && !mConnected && !mScanning) {
					Log.d("ActivityLifeText", "开始扫描蓝牙设备");
					scanLeDevice(true);
					readyTextView.setText(R.string.ble_detect_step_search);
				} else if (mConnected) {
					readyTextView.setText(R.string.ble_detect_step_linked);
				}
			}
		} else {
			Toast.makeText(DetectionActivity.this, R.string.ble_os_version_low,
					Toast.LENGTH_SHORT).show();
			readyTextView.setText(R.string.ble_not_support);
		}

	}
	
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.d("ActivityLifeText", "onCreate");

		setContentView(R.layout.detection);

		// 获取当前系统版本号（android4.3的版本号为18）
		int currentapiVersion = android.os.Build.VERSION.SDK_INT;

		if (currentapiVersion < 18) {
			Toast.makeText(DetectionActivity.this, R.string.ble_os_version_low,
					Toast.LENGTH_SHORT).show();
			supportBLE = false;
		} else {
			// 检查当前手机是否支持ble 蓝牙
			if (!getPackageManager().hasSystemFeature(
					PackageManager.FEATURE_BLUETOOTH_LE)) {
				Toast.makeText(DetectionActivity.this,
						R.string.ble_os_version_low, Toast.LENGTH_SHORT).show();
				supportBLE = false;
			} else {
				supportBLE = true;
				// 初始化 Bluetooth adapter,
				// 通过蓝牙管理器得到一个参考蓝牙适配器(API必须在以上android4.3或以上和版本)
				try {
					final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
					mBluetoothAdapter = bluetoothManager.getAdapter();
				} catch (NoClassDefFoundError e) {
					e.printStackTrace();
					Toast.makeText(DetectionActivity.this,
							R.string.ble_not_supported, Toast.LENGTH_SHORT)
							.show();
				}

				// 检查设备上是否支持蓝牙
				if (mBluetoothAdapter == null) {
					Toast.makeText(DetectionActivity.this,
							R.string.error_bluetooth_not_supported,
							Toast.LENGTH_SHORT).show();
					return;
				}
				// 为了确保设备上蓝牙能使用, 如果当前蓝牙设备没启用,弹出对话框向用户要求授予权限来启用
				if (!mBluetoothAdapter.isEnabled()) {
					Intent enableBtIntent = new Intent(
							BluetoothAdapter.ACTION_REQUEST_ENABLE);
					startActivityForResult(enableBtIntent,
							REQUEST_ENABLE_BT_CLICK);
				}
			}
		}

		readyTextView = (TextView) this.findViewById(R.id.ready);
		waterValue = (TextView) this.findViewById(R.id.watervalue);
		lvdevices = (ListView) this.findViewById(R.id.device_show_name);
		Button refresh=(Button) findViewById(R.id.refresh); 
		refresh.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				devicesNames.clear();
				adapter.notifyDataSetChanged();
				if(mConnected)
				mBluetoothLeService.disconnect();
				mConnected = false;
					mScanning = true;
					isNeedToScan=true; 
					Message message = new Message();
					message.what = DEVICE_DIS_CONNECT;
					handler.sendMessage(message);
			}
		});
		adapter = new ArrayAdapter<String>(this,
				android.R.layout.simple_list_item_1, devicesNames);
		lvdevices.setAdapter(adapter);
		lvdevices.setOnItemClickListener(new OnItemClickListener() {

			@Override
			public void onItemClick(AdapterView<?> parent, View view,
					int position, long id) {
				Toast.makeText(DetectionActivity.this,
						devicesNames.get(position), Toast.LENGTH_SHORT).show();
				mBluetoothAdapter.stopLeScan(mLeScanCallback);
				mScanning = false;
				isNeedToScan=false;
				mDeviceAddress=devicesAddress.get(position);
				if(!mConnected)
				mBluetoothLeService.connect(mDeviceAddress);
//				begainBindService();
			}
		});
//		handler.sendEmptyMessageDelayed(0, 1000);
	}

	public void setmDeviceAddress(String address) {
		mDeviceAddress = address;
	}
	/**
	 * 绑定服务的方法
	 */
	public void begainBindService(){
		Intent gattServiceIntent = new Intent(
				DetectionActivity.this,
				BluetoothLeService.class);
		bindService(gattServiceIntent, mServiceConnection,
				DetectionActivity.BIND_AUTO_CREATE);
	}
	private boolean mConnected = false;
	private boolean mScanning = false;

	public void scanLeDevice(final boolean enable) {
		final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
		mBluetoothAdapter = bluetoothManager.getAdapter();
		if (enable) {
			mScanning = true;
			mBluetoothAdapter.startLeScan(mLeScanCallback);
		} else {
			mScanning = false;
			mBluetoothAdapter.stopLeScan(mLeScanCallback);
		}
	}

	// Device scan callback.
	private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {

		@Override
		public void onLeScan(final BluetoothDevice device, int rssi,
				byte[] scanRecord) {
			Runnable rn = new Runnable() {
				@Override
				public void run() {
					try {
						boolean ifHaaved = false;
						for (int i = 0; i < devicesNames.size(); i++) {
							if (device.getName().equals(devicesNames.get(i))) {
								ifHaaved = true;
							}
						}
						if (!ifHaaved) {
							devicesNames.add(device.getName());
							devicesAddress.add(device.getAddress());
						}
						Message message=new Message();
						message.what=FIND_DIVECS;
						mBluetoothAdapter.stopLeScan(mLeScanCallback);
						handler.sendMessage(message); 
//						if (device != null && device.getName() != null
//								&& device.getName().equals("MyService")) {
//							if (mScanning) {
//								mBluetoothAdapter.stopLeScan(mLeScanCallback);
//								mScanning = false;
//							}
//							setmDeviceAddress(device.getAddress());
//							begainBindService();
//						}
					} catch (Exception e) {
						e.printStackTrace();
					}

				}
			};
			rn.run();
		}
	
	};

	// Code to manage Service lifecycle.
	public final ServiceConnection mServiceConnection = new ServiceConnection() {

		@Override
		/**
		 * 服务成功绑定时会调用
		 */
		public void onServiceConnected(ComponentName componentName,
				IBinder service) {
			mBluetoothLeService = ((BluetoothLeService.LocalBinder) service)
					.getService();
			if (!mBluetoothLeService.initialize()) {
				System.out.println("Unable to initialize Bluetooth");
				finish();
			}
			//mBluetoothLeService.connect(mDeviceAddress);
			isServiceReg = true;
		}

		@Override
		/**
		 * 事物成功解绑时会调用
		 */
		public void onServiceDisconnected(ComponentName componentName) {
			mBluetoothLeService = null;
			isServiceReg = false;
			Log.d("ActivityLifeText",
					"onServiceDisconnected!!!!!!!!!!!!!!!!!!!");
		}
	};

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		Log.d("ActivityLifeText", "onActivityResult");
		if (requestCode == REQUEST_ENABLE_BT_CLICK
				&& resultCode == Activity.RESULT_CANCELED) {
			Toast.makeText(DetectionActivity.this, R.string.ble_not_supported,
					Toast.LENGTH_SHORT).show();
			return;
		}else if(requestCode == REQUEST_ENABLE_BT_CLICK
				&& resultCode == Activity.RESULT_OK){
			Log.d("ActivityLifeText", "onActivityResult_ResultOK");
			return;
		}
		//super.onActivityResult(requestCode, resultCode, data);
	}

	@Override
	protected void onPause() {
		super.onPause();
	}

	@Override
	protected void onStop() {
		super.onStop();

		if (supportBLE && !receiverReleased) {
			if(mConnected)
				mBluetoothLeService.disconnect();
				mConnected = false;
			receiverReleased = true;
			unregisterReceiver(mGattUpdateReceiver);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		
	}

}
