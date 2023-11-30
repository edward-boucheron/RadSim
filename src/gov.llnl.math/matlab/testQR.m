import gov.llnl.math.*;
import gov.llnl.math.matrix.*;

A=[[0.30766600084529827, 0.7898073994899271, 0.8680654986163994, 0.19800582070852712]; [0.8418366809522624, 0.07414573946903857, 0.2752064778391272, 0.17224031711956655]; [0.01748007445991462, 0.5667222408666426, 0.04958256147299167, 0.8034808848370183]; [0.9687527735261767, 0.07932344614327747, 0.5002309704100867, 0.31155849604836305]]
Q=[[-0.23309803989753042, -0.6378035912472644, -0.013243488336944232, -0.7339594626440147]; [-0.6378035912472645, 0.6701045595354999, -0.006850018529464382, -0.3796307884352534]; [-0.013243488336944232, -0.0068500185294643814, 0.9998577647699891, -0.007882733788242502]; [-0.7339594626440147, -0.37963078843525333, -0.007882733788242502, 0.5631357155920416]]
R=[[-1.319899562349592, -0.2971185489203003, -0.7456779464451255, -0.39532245758101736]; [0.0, 0.0, -0.5594808960030234, -0.13465085814584443]; [0.0, 0.0, 0.0, 0.7971085317237494]; [0.0, 0.0, 0.0, -0.04159988245419338]]
T=[[-1.6653345369377348E-16, -0.7205496481194066, -0.33741050620025936, -8.326672684688674E-17]; [0.0, 0.11535753805850502, -0.17452110506717355, 2.7755575615628914E-17]; [0.0, -0.5627873548293267, -0.03587472978162708, -1.1102230246251565E-16]; [-1.1102230246251565E-16, 0.13874952436383556, 0.2594625881323598, -5.551115123125783E-17]]

am=DoubleMatrix(A);

am.toArray()-A

[q,r]=qr(A);

qrd=QRDecomposition();
qrd.compute(am);

Q1=MatrixOps.toArray(qrd.q);
R1=MatrixOps.toArray(qrd.r);
