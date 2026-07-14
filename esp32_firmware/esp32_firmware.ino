#include <WiFi.h>
#include <esp_wifi.h>
#include <ArduinoJson.h>

// Global variables
String currentSSID = "";
String currentPassword = "";
bool isConfigured = false;
#define LED_PIN 2

// Structures to hold discovered devices
struct DiscoveredDevice {
  uint8_t mac[6];
  int8_t rssi;
  uint8_t bssid[6];
  unsigned long lastSeen;
};

#define MAX_DEVICES 100
DiscoveredDevice devices[MAX_DEVICES];
int deviceCount = 0;

// Structures to hold discovered routers (APs)
struct DiscoveredRouter {
  uint8_t bssid[6];
  int8_t rssi;
  unsigned long lastSeen;
};

#define MAX_ROUTERS 20
DiscoveredRouter routers[MAX_ROUTERS];
int routerCount = 0;

// Promiscuous callback
void promiscuous_rx_cb(void *buf, wifi_promiscuous_pkt_type_t type) {
  if (type != WIFI_PKT_MGMT && type != WIFI_PKT_DATA) return;
  
  wifi_promiscuous_pkt_t *pkt = (wifi_promiscuous_pkt_t *)buf;
  int8_t rssi = pkt->rx_ctrl.rssi;
  uint8_t *payload = pkt->payload;
  
  uint8_t frameType = payload[0] & 0x0C;
  uint8_t frameSubtype = payload[0] & 0xF0;
  
  uint8_t* addr2 = payload + 10; // Transmitter MAC
  uint8_t* addr3 = payload + 16; // BSSID
  
  if (frameType == 0x00 && frameSubtype == 0x80) { // Beacon frame
    bool found = false;
    for (int i = 0; i < routerCount; i++) {
      if (memcmp(routers[i].bssid, addr3, 6) == 0) {
        routers[i].rssi = rssi;
        routers[i].lastSeen = millis();
        found = true;
        break;
      }
    }
    if (!found && routerCount < MAX_ROUTERS) {
      memcpy(routers[routerCount].bssid, addr3, 6);
      routers[routerCount].rssi = rssi;
      routers[routerCount].lastSeen = millis();
      routerCount++;
    }
  } 
  else if (frameType == 0x08) { // Data frame
    bool found = false;
    for (int i = 0; i < deviceCount; i++) {
      if (memcmp(devices[i].mac, addr2, 6) == 0) {
        devices[i].rssi = rssi;
        memcpy(devices[i].bssid, addr3, 6);
        devices[i].lastSeen = millis();
        found = true;
        break;
      }
    }
    if (!found && deviceCount < MAX_DEVICES) {
      memcpy(devices[deviceCount].mac, addr2, 6);
      memcpy(devices[deviceCount].bssid, addr3, 6);
      devices[deviceCount].rssi = rssi;
      devices[deviceCount].lastSeen = millis();
      deviceCount++;
    }
  }
}

void setup() {
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH); // Stay constant when board is powered/connected
  Serial.begin(921600);
  while (!Serial) { ; }
  
  Serial.println("{\"status\": \"ready_for_config\"}");
  
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
}

void loop() {
  if (Serial.available()) {
    String input = Serial.readStringUntil('\n');
    input.trim();
    if (input.startsWith("{")) {
      StaticJsonDocument<256> doc;
      DeserializationError err = deserializeJson(doc, input);
      if (!err && doc.containsKey("ssid") && doc.containsKey("password")) {
        currentSSID = doc["ssid"].as<String>();
        currentPassword = doc["password"].as<String>();
        isConfigured = true;
        connectToWiFi();
      }
    }
  }
  
  if (isConfigured) {
    static unsigned long lastSend = 0;
    if (millis() - lastSend > 200) { // Communicate fast
      sendDataToAndroid();
      lastSend = millis();
    }
  }
}

void connectToWiFi() {
  Serial.println("{\"status\": \"connecting\", \"ssid\": \"" + currentSSID + "\"}");
  WiFi.begin(currentSSID.c_str(), currentPassword.c_str());
  
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 20) {
    delay(500);
    attempts++;
  }
  
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("{\"status\": \"connected\", \"ip\": \"" + WiFi.localIP().toString() + "\"}");
    esp_wifi_set_promiscuous(true);
    esp_wifi_set_promiscuous_rx_cb(&promiscuous_rx_cb);
  } else {
    Serial.println("{\"status\": \"failed\"}");
    isConfigured = false;
  }
}

String macToString(uint8_t* mac) {
  char buf[18];
  snprintf(buf, sizeof(buf), "%02x:%02x:%02x:%02x:%02x:%02x", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
  return String(buf);
}

void sendDataToAndroid() {
  DynamicJsonDocument doc(4096);
  doc["type"] = "scan_results";
  
  JsonArray routersArr = doc.createNestedArray("routers");
  unsigned long now = millis();
  for (int i = 0; i < routerCount; i++) {
    if (now - routers[i].lastSeen < 10000) {
      JsonObject r = routersArr.createNestedObject();
      r["bssid"] = macToString(routers[i].bssid);
      r["rssi"] = routers[i].rssi;
    }
  }
  
  JsonArray devicesArr = doc.createNestedArray("devices");
  for (int i = 0; i < deviceCount; i++) {
    if (now - devices[i].lastSeen < 10000) {
      JsonObject d = devicesArr.createNestedObject();
      d["mac"] = macToString(devices[i].mac);
      d["bssid"] = macToString(devices[i].bssid);
      d["rssi"] = devices[i].rssi;
    }
  }
  
  String output;
  serializeJson(doc, output);
  Serial.println(output);
}
