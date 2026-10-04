package com.vinlanx.luxium.Testing.ExplosionTesting;

import java.util.Arrays;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class ExplosionFluidGrid {
   static final int NX = 22;
   static final int NY = 19;
   static final int NZ = 22;
   private static final int COUNT = 9196;
   private final float[] vx = new float[9196];
   private final float[] vy = new float[9196];
   private final float[] vz = new float[9196];
   private final float[] temperature = new float[9196];
   private final float[] smoke = new float[9196];
   private final float[] fuel = new float[9196];
   private final float[] expansion = new float[9196];
   private final float[] pressure = new float[9196];
   private final boolean[] solid = new boolean[9196];
   private final float[] scratch = new float[9196];
   private final float[] advectVx = new float[9196];
   private final float[] advectVy = new float[9196];
   private final float[] advectVz = new float[9196];
   private final float[] curlX = new float[9196];
   private final float[] curlY = new float[9196];
   private final float[] curlZ = new float[9196];
   private final float[] curlMagnitude = new float[9196];
   private final float[] nextPressure = new float[9196];
   private double minX;
   private double minY;
   private double minZ;
   private boolean initialized;

   void reset() {
      Arrays.fill(this.vx, 0.0F);
      Arrays.fill(this.vy, 0.0F);
      Arrays.fill(this.vz, 0.0F);
      Arrays.fill(this.temperature, 0.0F);
      Arrays.fill(this.smoke, 0.0F);
      Arrays.fill(this.fuel, 0.0F);
      Arrays.fill(this.expansion, 0.0F);
      Arrays.fill(this.pressure, 0.0F);
      this.initialized = false;
   }

   void centerOn(ExplosionCollisionCache collision, Vec3 center) {
      this.minX = center.x - 11.0;
      this.minY = center.y - 1.0;
      this.minZ = center.z - 11.0;
      this.rebuildSolids(collision);
      this.initialized = true;
   }

   void inject(ExplosionCollisionCache collision, Vec3 center, ExplosionProfile profile) {
      if (!this.initialized || center.distanceToSqr(this.minX + 11.0, this.minY + 9.5, this.minZ + 11.0) > 64.0) {
         this.reset();
         this.centerOn(collision, center);
      }

      float radius = profile.coreRadius();
      float impulse = profile.blastStrength();
      boolean ground = center.y < Math.max(1.5, (double)radius * 0.42);

      for (int y = 1; y < 18; y++) {
         for (int z = 1; z < 21; z++) {
            for (int x = 1; x < 21; x++) {
               int i = index(x, y, z);
               if (!this.solid[i]) {
                  double px = this.minX + (double)x + 0.5;
                  double py = this.minY + (double)y + 0.5;
                  double pz = this.minZ + (double)z + 0.5;
                  float ox = (float)(px - center.x);
                  float oy = (float)(py - center.y);
                  float oz = (float)(pz - center.z);
                  float distance = (float)Math.sqrt((double)(ox * ox + oy * oy + oz * oz));
                  float inv = 1.0F / Math.max(distance, 0.08F);
                  ox *= inv;
                  oy *= inv;
                  oz *= inv;
                  if (ground) {
                     oy = Math.abs(oy) * 0.78F + 0.12F;
                     float norm = Mth.invSqrt(ox * ox + oy * oy + oz * oz);
                     ox *= norm;
                     oy *= norm;
                     oz *= norm;
                  }

                  float q = distance / radius;
                  float weight = (float)Math.exp((double)(-(q * q) * 1.65F));
                  this.vx[i] = this.vx[i] + ox * weight * impulse;
                  this.vy[i] = this.vy[i] + oy * weight * impulse + weight * impulse * 0.24F;
                  this.vz[i] = this.vz[i] + oz * weight * impulse;
                  this.temperature[i] = this.temperature[i] + weight * 2.8F * profile.heatMultiplier();
                  this.fuel[i] = this.fuel[i] + weight * 1.35F * profile.heatMultiplier();
                  this.smoke[i] = this.smoke[i] + weight * (0.16F + 0.025F * profile.scale());
                  this.expansion[i] = this.expansion[i] + weight * 3.0F * (float)Math.pow((double)profile.scale(), 0.24);
               }
            }
         }
      }

      this.applySolidBoundary();
   }

   void injectAfterburn(Vec3 center, ExplosionProfile profile, float age) {
      if (this.initialized) {
         float radius = profile.coreRadius() * 0.56F + age * (0.28F + 0.1F * profile.scale());
         float pulse = (float)Math.exp((double)(-age * (3.0F / profile.durationScale())));
         float strength = profile.blastStrength();

         for (int y = 1; y < 18; y++) {
            for (int z = 1; z < 21; z++) {
               for (int x = 1; x < 21; x++) {
                  int i = index(x, y, z);
                  if (!this.solid[i]) {
                     float ox = (float)(this.minX + (double)x + 0.5 - center.x);
                     float oy = (float)(this.minY + (double)y + 0.5 - center.y);
                     float oz = (float)(this.minZ + (double)z + 0.5 - center.z);
                     float distance = (float)Math.sqrt((double)(ox * ox + oy * oy + oz * oz));
                     float q = distance / radius;
                     float weight = (float)Math.exp((double)(-(q * q) * 2.1F));
                     this.temperature[i] = this.temperature[i] + weight * pulse * 0.13F * strength * profile.heatMultiplier();
                     this.fuel[i] = this.fuel[i] + weight * pulse * 0.045F * strength * profile.heatMultiplier();
                     this.expansion[i] = this.expansion[i] + weight * pulse * 0.06F * strength * (float)Math.pow((double)profile.scale(), 0.22);
                     this.vy[i] = this.vy[i] + weight * pulse * 0.12F * strength;
                  }
               }
            }
         }
      }
   }

   void step(float dt) {
      if (this.initialized) {
         dt = Mth.clamp(dt, 0.0F, 0.04F);
         if (!(dt <= 0.0F)) {
            System.arraycopy(this.vx, 0, this.advectVx, 0, 9196);
            System.arraycopy(this.vy, 0, this.advectVy, 0, 9196);
            System.arraycopy(this.vz, 0, this.advectVz, 0, 9196);
            this.advect(this.vx, dt);
            this.advect(this.vy, dt);
            this.advect(this.vz, dt);
            this.advect(this.temperature, dt);
            this.advect(this.smoke, dt);
            this.advect(this.fuel, dt);
            this.advect(this.expansion, dt);

            for (int i = 0; i < 9196; i++) {
               if (!this.solid[i]) {
                  float ignition = Mth.clamp((this.temperature[i] - 0.32F) * 2.4F, 0.0F, 1.0F);
                  float burn = Math.min(this.fuel[i], this.fuel[i] * ignition * dt * 3.4F);
                  this.fuel[i] = this.fuel[i] - burn;
                  this.temperature[i] = this.temperature[i] + burn * 2.5F;
                  this.smoke[i] = this.smoke[i] + burn * 0.82F;
                  this.expansion[i] = this.expansion[i] + burn * 2.2F;
                  this.vy[i] = this.vy[i] + dt * (this.temperature[i] * 3.0F - this.smoke[i] * 0.24F);
               }
            }

            this.vorticity(dt, 2.3F);
            this.projectPressure();
            float velocityDecay = (float)Math.exp((double)(-dt * 0.18F));
            float heatDecay = (float)Math.exp((double)(-dt * 0.78F));
            float smokeDecay = (float)Math.exp((double)(-dt * 0.075F));
            float fuelDecay = (float)Math.exp((double)(-dt * 0.12F));
            float expansionDecay = (float)Math.exp((double)(-dt * 4.2F));

            for (int ix = 0; ix < 9196; ix++) {
               this.vx[ix] = this.vx[ix] * velocityDecay;
               this.vy[ix] = this.vy[ix] * velocityDecay;
               this.vz[ix] = this.vz[ix] * velocityDecay;
               this.temperature[ix] = Mth.clamp(this.temperature[ix] * heatDecay, 0.0F, 5.0F);
               this.smoke[ix] = Mth.clamp(this.smoke[ix] * smokeDecay, 0.0F, 4.0F);
               this.fuel[ix] = Mth.clamp(this.fuel[ix] * fuelDecay, 0.0F, 3.0F);
               this.expansion[ix] = this.expansion[ix] * expansionDecay;
            }

            this.applySolidBoundary();
         }
      }
   }

   Vec3 sampleVelocity(double x, double y, double z) {
      return new Vec3((double)this.sample(this.vx, x, y, z), (double)this.sample(this.vy, x, y, z), (double)this.sample(this.vz, x, y, z));
   }

   float sampleTemperature(double x, double y, double z) {
      return this.sample(this.temperature, x, y, z);
   }

   private void rebuildSolids(ExplosionCollisionCache collision) {
      for (int y = 0; y < 19; y++) {
         for (int z = 0; z < 22; z++) {
            for (int x = 0; x < 22; x++) {
               int i = index(x, y, z);
               boolean boundary = x == 0 || x == 21 || y == 0 || y == 18 || z == 0 || z == 21;
               this.solid[i] = boundary || collision.isSolid(this.minX + (double)x + 0.5, this.minY + (double)y + 0.5, this.minZ + (double)z + 0.5);
            }
         }
      }
   }

   private void advect(float[] field, float dt) {
      for (int y = 0; y < 19; y++) {
         for (int z = 0; z < 22; z++) {
            for (int x = 0; x < 22; x++) {
               int i = index(x, y, z);
               double px = this.minX + (double)x + 0.5 - (double)(this.advectVx[i] * dt);
               double py = this.minY + (double)y + 0.5 - (double)(this.advectVy[i] * dt);
               double pz = this.minZ + (double)z + 0.5 - (double)(this.advectVz[i] * dt);
               this.scratch[i] = this.sample(field, px, py, pz);
            }
         }
      }

      System.arraycopy(this.scratch, 0, field, 0, 9196);
   }

   private void vorticity(float dt, float strength) {
      Arrays.fill(this.curlX, 0.0F);
      Arrays.fill(this.curlY, 0.0F);
      Arrays.fill(this.curlZ, 0.0F);
      Arrays.fill(this.curlMagnitude, 0.0F);

      for (int y = 1; y < 18; y++) {
         for (int z = 1; z < 21; z++) {
            for (int x = 1; x < 21; x++) {
               int i = index(x, y, z);
               this.curlX[i] = (this.vz[index(x, y + 1, z)] - this.vz[index(x, y - 1, z)]) * 0.5F
                  - (this.vy[index(x, y, z + 1)] - this.vy[index(x, y, z - 1)]) * 0.5F;
               this.curlY[i] = (this.vx[index(x, y, z + 1)] - this.vx[index(x, y, z - 1)]) * 0.5F
                  - (this.vz[index(x + 1, y, z)] - this.vz[index(x - 1, y, z)]) * 0.5F;
               this.curlZ[i] = (this.vy[index(x + 1, y, z)] - this.vy[index(x - 1, y, z)]) * 0.5F
                  - (this.vx[index(x, y + 1, z)] - this.vx[index(x, y - 1, z)]) * 0.5F;
               this.curlMagnitude[i] = (float)Math.sqrt((double)(this.curlX[i] * this.curlX[i] + this.curlY[i] * this.curlY[i] + this.curlZ[i] * this.curlZ[i]));
            }
         }
      }

      for (int y = 2; y < 17; y++) {
         for (int z = 2; z < 20; z++) {
            for (int x = 2; x < 20; x++) {
               int i = index(x, y, z);
               float nx = (this.curlMagnitude[index(x + 1, y, z)] - this.curlMagnitude[index(x - 1, y, z)]) * 0.5F;
               float ny = (this.curlMagnitude[index(x, y + 1, z)] - this.curlMagnitude[index(x, y - 1, z)]) * 0.5F;
               float nz = (this.curlMagnitude[index(x, y, z + 1)] - this.curlMagnitude[index(x, y, z - 1)]) * 0.5F;
               float inv = Mth.invSqrt(nx * nx + ny * ny + nz * nz + 1.0E-5F);
               nx *= inv;
               ny *= inv;
               nz *= inv;
               this.vx[i] = this.vx[i] + (ny * this.curlZ[i] - nz * this.curlY[i]) * dt * strength;
               this.vy[i] = this.vy[i] + (nz * this.curlX[i] - nx * this.curlZ[i]) * dt * strength;
               this.vz[i] = this.vz[i] + (nx * this.curlY[i] - ny * this.curlX[i]) * dt * strength;
            }
         }
      }
   }

   private void projectPressure() {
      Arrays.fill(this.scratch, 0.0F);

      for (int y = 1; y < 18; y++) {
         for (int z = 1; z < 21; z++) {
            for (int x = 1; x < 21; x++) {
               int i = index(x, y, z);
               this.scratch[i] = (
                        this.vx[index(x + 1, y, z)]
                           - this.vx[index(x - 1, y, z)]
                           + this.vy[index(x, y + 1, z)]
                           - this.vy[index(x, y - 1, z)]
                           + this.vz[index(x, y, z + 1)]
                           - this.vz[index(x, y, z - 1)]
                     )
                     * 0.5F
                  - this.expansion[i];
            }
         }
      }

      Arrays.fill(this.pressure, 0.0F);

      for (int iteration = 0; iteration < 7; iteration++) {
         Arrays.fill(this.nextPressure, 0.0F);

         for (int y = 1; y < 18; y++) {
            for (int z = 1; z < 21; z++) {
               for (int x = 1; x < 21; x++) {
                  int i = index(x, y, z);
                  if (!this.solid[i]) {
                     this.nextPressure[i] = (
                           this.pressure[index(x + 1, y, z)]
                              + this.pressure[index(x - 1, y, z)]
                              + this.pressure[index(x, y + 1, z)]
                              + this.pressure[index(x, y - 1, z)]
                              + this.pressure[index(x, y, z + 1)]
                              + this.pressure[index(x, y, z - 1)]
                              - this.scratch[i]
                        )
                        / 6.0F;
                  }
               }
            }
         }

         System.arraycopy(this.nextPressure, 0, this.pressure, 0, 9196);
      }

      for (int y = 1; y < 18; y++) {
         for (int z = 1; z < 21; z++) {
            for (int xx = 1; xx < 21; xx++) {
               int i = index(xx, y, z);
               this.vx[i] = this.vx[i] - (this.pressure[index(xx + 1, y, z)] - this.pressure[index(xx - 1, y, z)]) * 0.5F;
               this.vy[i] = this.vy[i] - (this.pressure[index(xx, y + 1, z)] - this.pressure[index(xx, y - 1, z)]) * 0.5F;
               this.vz[i] = this.vz[i] - (this.pressure[index(xx, y, z + 1)] - this.pressure[index(xx, y, z - 1)]) * 0.5F;
            }
         }
      }
   }

   private float sample(float[] field, double worldX, double worldY, double worldZ) {
      float gx = Mth.clamp((float)(worldX - this.minX - 0.5), 0.0F, 20.999F);
      float gy = Mth.clamp((float)(worldY - this.minY - 0.5), 0.0F, 17.999F);
      float gz = Mth.clamp((float)(worldZ - this.minZ - 0.5), 0.0F, 20.999F);
      int x0 = Mth.floor(gx);
      int y0 = Mth.floor(gy);
      int z0 = Mth.floor(gz);
      int x1 = Math.min(x0 + 1, 21);
      int y1 = Math.min(y0 + 1, 18);
      int z1 = Math.min(z0 + 1, 21);
      float fx = gx - (float)x0;
      float fy = gy - (float)y0;
      float fz = gz - (float)z0;
      float c00 = Mth.lerp(fx, field[index(x0, y0, z0)], field[index(x1, y0, z0)]);
      float c10 = Mth.lerp(fx, field[index(x0, y1, z0)], field[index(x1, y1, z0)]);
      float c01 = Mth.lerp(fx, field[index(x0, y0, z1)], field[index(x1, y0, z1)]);
      float c11 = Mth.lerp(fx, field[index(x0, y1, z1)], field[index(x1, y1, z1)]);
      return Mth.lerp(fz, Mth.lerp(fy, c00, c10), Mth.lerp(fy, c01, c11));
   }

   private void applySolidBoundary() {
      for (int i = 0; i < 9196; i++) {
         if (this.solid[i]) {
            this.vx[i] = this.vy[i] = this.vz[i] = this.temperature[i] = this.smoke[i] = this.fuel[i] = this.expansion[i] = 0.0F;
         }
      }
   }

   private static int index(int x, int y, int z) {
      return (y * 22 + z) * 22 + x;
   }
}
