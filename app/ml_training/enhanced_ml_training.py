#!/usr/bin/env python3
"""
Complete ML Training Pipeline for Digital Wellness Monitoring
Generates synthetic data and trains models for behavioral pattern recognition
"""

import pandas as pd
import numpy as np
from sklearn.ensemble import RandomForestClassifier, GradientBoostingClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, accuracy_score
import tensorflow as tf
from tensorflow.keras.layers import Dense, Dropout, Input
from tensorflow.keras.models import Model
import joblib
import os

class DigitalWellnessML:
    def __init__(self):
        self.scaler = StandardScaler()
        self.models = {}
        
    def generate_realistic_data(self, n_samples=15000):
        """Generate realistic synthetic data with behavioral correlations"""
        np.random.seed(42)
        data = []
        
        app_categories = list(range(10))
        category_names = {
            0: "social", 1: "productivity", 2: "entertainment", 3: "games",
            4: "news", 5: "shopping", 6: "communication", 7: "health", 
            8: "finance", 9: "utilities"
        }
        
        for i in range(n_samples):
            # Time patterns affect usage
            time_of_day = np.random.uniform(0, 1)
            
            # Morning patterns (6-9 AM)
            if 0.25 <= time_of_day <= 0.375:
                duration_base = np.random.lognormal(7.5, 0.8) * 1000
                category = np.random.choice([1, 4, 6], p=[0.5, 0.3, 0.2])
                unlock_base = np.random.poisson(5)
                
            # Evening patterns (7-11 PM) - higher risk
            elif 0.79 <= time_of_day <= 0.96:
                duration_base = np.random.lognormal(9.5, 1.3) * 1000
                category = np.random.choice([0, 2, 3], p=[0.6, 0.25, 0.15])
                unlock_base = np.random.poisson(20)
                
            # Work hours (9 AM - 5 PM)
            elif 0.375 <= time_of_day <= 0.708:
                duration_base = np.random.lognormal(8.5, 1.0) * 1000
                category = np.random.choice(app_categories)
                unlock_base = np.random.poisson(12)
                
            # Late night/early morning
            else:
                duration_base = np.random.lognormal(7, 0.9) * 1000
                category = np.random.choice([0, 2, 6], p=[0.4, 0.4, 0.2])
                unlock_base = np.random.poisson(3)
            
            session_duration = max(duration_base, 30000)  # Min 30 seconds
            unlock_count = max(unlock_base, 1)
            app_name = category_names[category]
            
            # Feature correlations
            notif_count = np.random.poisson(4 if category in [0, 2, 6] else 1)
            notif_response = np.random.choice([0, 1, 2], 
                                           p=[0.4, 0.35, 0.25] if category in [0, 2] 
                                           else [0.6, 0.3, 0.1])
            
            app_switch_count = max(0, int(session_duration / 300000) + np.random.poisson(1))
            
            consecutive_minutes = int(session_duration / 60000)
            if category in [0, 2, 3]:  # Addictive apps
                consecutive_minutes += np.random.poisson(15)
            
            binge_flag = 1 if session_duration > 2 * 3600 * 1000 else 0
            
            scrolls_per_minute = np.random.gamma(
                shape=12 if category in [0, 2] else 4, scale=1
            )
            
            unlock_frequency = (unlock_count * 3600000) / session_duration
            
            # Target variables with realistic correlations
            risk_factors = [
                category in [0, 2, 3],  # High-stimulation apps
                consecutive_minutes > 60,  # Long usage
                scrolls_per_minute > 8,  # High interaction
                unlock_frequency > 25,  # Frequent unlocks
                binge_flag == 1,  # Binge session
                time_of_day > 0.79 or time_of_day < 0.25  # Evening/late usage
            ]
            
            dopamine_prob = min(0.95, sum(risk_factors) / len(risk_factors) + np.random.normal(0, 0.1))
            dopamine_spike = 1 if np.random.random() < dopamine_prob else 0
            
            # Addiction scoring
            addiction_score = (
                (session_duration / (6 * 3600 * 1000)) * 0.25 +
                (unlock_count / 40) * 0.2 +
                (category in [0, 2, 3]) * 0.2 +
                dopamine_spike * 0.15 +
                (consecutive_minutes / 120) * 0.2
            )
            
            if addiction_score < 0.25:
                addiction_level = 0  # Healthy
            elif addiction_score < 0.65:
                addiction_level = 1  # At risk
            else:
                addiction_level = 2  # High risk
            
            data.append([
                f'user_{i % 1000}',
                session_duration,
                unlock_count,
                app_name,
                category,
                notif_count,
                notif_response,
                app_switch_count,
                time_of_day,
                consecutive_minutes,
                binge_flag,
                scrolls_per_minute,
                unlock_frequency,
                dopamine_spike,
                addiction_level
            ])
        
        columns = [
            'user_id', 'session_duration', 'unlock_count', 'app_name', 'app_category',
            'notif_count', 'notif_response', 'app_switch_count', 'time_of_day',
            'consecutive_same_app', 'binge_flag', 'scrolls_per_minute',
            'unlock_frequency', 'dopamine_spike_flag', 'addiction_flag'
        ]
        
        return pd.DataFrame(data, columns=columns)
    
    def engineer_features(self, df):
        """Create engineered features"""
        features = df[[
            'session_duration', 'unlock_count', 'app_category', 'notif_count',
            'notif_response', 'app_switch_count', 'time_of_day',
            'consecutive_same_app', 'binge_flag', 'scrolls_per_minute',
            'unlock_frequency'
        ]].copy()
        
        # Engineered features
        features['duration_hours'] = features['session_duration'] / (1000 * 3600)
        features['high_stim_app'] = features['app_category'].isin([0, 2, 3]).astype(int)
        features['notif_responsiveness'] = features['notif_response'] / 2.0
        features['usage_intensity'] = (features['unlock_frequency'] * features['scrolls_per_minute']) / 100
        features['evening_usage'] = ((features['time_of_day'] >= 0.79) | 
                                   (features['time_of_day'] <= 0.25)).astype(int)
        
        return features
    
    def train_dopamine_model(self, features, targets):
        """Train dopamine spike prediction model"""
        X_train, X_test, y_train, y_test = train_test_split(
            features, targets, test_size=0.2, random_state=42, stratify=targets
        )
        
        # Scale features
        X_train_scaled = self.scaler.fit_transform(X_train)
        X_test_scaled = self.scaler.transform(X_test)
        
        # Train Random Forest
        rf_model = RandomForestClassifier(
            n_estimators=100, max_depth=12, min_samples_split=10,
            random_state=42, class_weight='balanced'
        )
        rf_model.fit(X_train_scaled, y_train)
        
        # Evaluate
        train_acc = accuracy_score(y_train, rf_model.predict(X_train_scaled))
        test_acc = accuracy_score(y_test, rf_model.predict(X_test_scaled))
        
        print(f"Dopamine Model - Train: {train_acc:.3f}, Test: {test_acc:.3f}")
        
        self.models['dopamine_rf'] = rf_model
        return rf_model, X_train_scaled, y_train
    
    def train_addiction_model(self, features, targets):
        """Train addiction level classifier"""
        X_train, X_test, y_train, y_test = train_test_split(
            features, targets, test_size=0.2, random_state=42, stratify=targets
        )
        
        X_train_scaled = self.scaler.transform(X_train)
        X_test_scaled = self.scaler.transform(X_test)
        
        # Gradient Boosting Classifier
        gb_model = GradientBoostingClassifier(
            n_estimators=100, learning_rate=0.1, max_depth=6, random_state=42
        )
        gb_model.fit(X_train_scaled, y_train)
        
        train_acc = accuracy_score(y_train, gb_model.predict(X_train_scaled))
        test_acc = accuracy_score(y_test, gb_model.predict(X_test_scaled))
        
        print(f"Addiction Model - Train: {train_acc:.3f}, Test: {test_acc:.3f}")
        print("Classification Report:")
        print(classification_report(y_test, gb_model.predict(X_test_scaled),
                                  target_names=['Healthy', 'At Risk', 'High Risk']))
        
        self.models['addiction_gb'] = gb_model
        return gb_model
    
    def create_tensorflow_models(self, features, dopamine_targets, addiction_targets):
        """Create TensorFlow models for mobile deployment"""
        
        # Dopamine model
        input_layer = Input(shape=(features.shape[1],))
        x = Dense(64, activation='relu')(input_layer)
        x = Dropout(0.3)(x)
        x = Dense(32, activation='relu')(x)
        x = Dropout(0.2)(x)
        dopamine_output = Dense(1, activation='sigmoid', name='dopamine')(x)
        
        dopamine_model = Model(inputs=input_layer, outputs=dopamine_output)
        dopamine_model.compile(optimizer='adam', loss='binary_crossentropy', metrics=['accuracy'])
        
        # Addiction model
        addiction_x = Dense(64, activation='relu')(input_layer)
        addiction_x = Dropout(0.3)(addiction_x)
        addiction_x = Dense(32, activation='relu')(addiction_x)
        addiction_output = Dense(3, activation='softmax', name='addiction')(addiction_x)
        
        addiction_model = Model(inputs=input_layer, outputs=addiction_output)
        addiction_model.compile(optimizer='adam', loss='sparse_categorical_crossentropy', metrics=['accuracy'])
        
        return dopamine_model, addiction_model
    
    def train_and_convert_models(self, features, dopamine_targets, addiction_targets):
        """Train TensorFlow models and convert to TFLite"""
        
        # Split data
        X_train, X_test, y_dop_train, y_dop_test, y_add_train, y_add_test = train_test_split(
            features, dopamine_targets, addiction_targets, test_size=0.2, random_state=42
        )
        
        # Scale features
        X_train_scaled = self.scaler.fit_transform(X_train)
        X_test_scaled = self.scaler.transform(X_test)
        
        # Create models
        dopamine_model, addiction_model = self.create_tensorflow_models(
            X_train_scaled, y_dop_train, y_add_train
        )
        
        # Train dopamine model
        dopamine_model.fit(
            X_train_scaled, y_dop_train,
            epochs=50, batch_size=32, validation_split=0.2, verbose=0
        )
        
        # Train addiction model
        addiction_model.fit(
            X_train_scaled, y_add_train,
            epochs=50, batch_size=32, validation_split=0.2, verbose=0
        )
        
        # Evaluate
        dop_acc = dopamine_model.evaluate(X_test_scaled, y_dop_test, verbose=0)[1]
        add_acc = addiction_model.evaluate(X_test_scaled, y_add_test, verbose=0)[1]
        
        print(f"TensorFlow Dopamine Model Test Accuracy: {dop_acc:.3f}")
        print(f"TensorFlow Addiction Model Test Accuracy: {add_acc:.3f}")
        
        # Convert to TFLite
        self.convert_to_tflite(dopamine_model, 'dopamine_model.tflite')
        self.convert_to_tflite(addiction_model, 'addiction_model.tflite')
        
        # Save scaler (Python format)
        joblib.dump(self.scaler, 'feature_scaler.pkl')
        
        # Export scaler config as JSON for Android dynamic loading
        self.export_scaler_json('scaler_config.json')
        
        return dopamine_model, addiction_model
    
    def export_scaler_json(self, filename):
        """Export StandardScaler parameters as JSON for Android assets.
        
        This file is loaded by TFLiteModelHelper.java at runtime,
        eliminating the need to hardcode scaler constants in Java.
        """
        import json
        
        scaler_config = {
            "means": self.scaler.mean_.tolist(),
            "scales": self.scaler.scale_.tolist()
        }
        
        with open(filename, 'w') as f:
            json.dump(scaler_config, f, indent=2)
        
        print(f"✅ Exported {filename} ({len(self.scaler.mean_)} features)")
    
    def convert_to_tflite(self, model, filename):
        """Convert Keras model to TensorFlow Lite"""
        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        # Removed optimizations to prevent FULLY_CONNECTED version 12 requirement mismatch
        
        # Ensure compatibility
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS
        ]
        converter.experimental_new_converter = True
        
        tflite_model = converter.convert()
        
        with open(filename, 'wb') as f:
            f.write(tflite_model)
        
        print(f"✅ Created {filename} ({len(tflite_model)/1024:.1f} KB)")

def main():
    """Main training pipeline"""
    print("🧠 Digital Wellness ML Training Pipeline")
    print("=" * 50)
    
    # Initialize trainer
    trainer = DigitalWellnessML()
    
    # Generate data
    print("📊 Generating synthetic dataset...")
    df = trainer.generate_realistic_data(15000)
    print(f"Dataset shape: {df.shape}")
    
    # Feature engineering
    print("\n🔧 Engineering features...")
    features = trainer.engineer_features(df)
    print(f"Feature matrix shape: {features.shape}")
    
    # Targets
    dopamine_targets = df['dopamine_spike_flag'].values
    addiction_targets = df['addiction_flag'].values
    
    print(f"Dopamine spike distribution: {np.bincount(dopamine_targets)}")
    print(f"Addiction level distribution: {np.bincount(addiction_targets)}")
    
    # Train sklearn models (for comparison)
    print("\n🎯 Training sklearn models...")
    trainer.train_dopamine_model(features, dopamine_targets)
    trainer.train_addiction_model(features, addiction_targets)
    
    # Train and convert TensorFlow models
    print("\n🤖 Training TensorFlow models for mobile deployment...")
    trainer.train_and_convert_models(features, dopamine_targets, addiction_targets)
    
    print("\n✅ Training complete! Generated files:")
    print("  - dopamine_model.tflite")
    print("  - addiction_model.tflite")
    print("  - feature_scaler.pkl")
    print("  - scaler_config.json  (for Android dynamic loading)")
    print("\n📱 Copy .tflite + scaler_config.json to Android app's assets/ folder")

if __name__ == "__main__":
    main()